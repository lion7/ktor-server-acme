package com.github.lion7.ktor.server.acme

import io.ktor.server.engine.ConnectorType
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.EngineSSLConnectorConfig
import io.ktor.server.jetty.JettyApplicationEngineBase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.shredzone.acme4j.Authorization
import org.shredzone.acme4j.Certificate
import org.shredzone.acme4j.Identifier
import org.shredzone.acme4j.Order
import org.shredzone.acme4j.Status
import org.shredzone.acme4j.challenge.TlsAlpn01Challenge
import org.shredzone.acme4j.util.CSRBuilder
import org.shredzone.acme4j.util.CertificateUtils
import org.shredzone.acme4j.util.KeyPairUtils
import org.slf4j.LoggerFactory
import java.io.File
import java.security.KeyPair
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

fun acmeConnector(
    certificateAuthority: String,
    accountKeyPairFile: File,
    contact: String,
    agreeToTermsOfService: Boolean,
    domain: String,
    keyStorePath: File,
    keyStorePassword: () -> CharArray,
    privateKeyPassword: () -> CharArray,
    builder: AcmeConnector.() -> Unit = {}
): AcmeConnector = acmeConnector(
    AcmeAccountManager(certificateAuthority, accountKeyPairFile, contact, agreeToTermsOfService),
    domain, keyStorePath, keyStorePassword, privateKeyPassword, builder
)

fun acmeConnector(
    accountManager: AcmeAccountManager,
    domain: String,
    keyStorePath: File,
    keyStorePassword: () -> CharArray,
    privateKeyPassword: () -> CharArray,
    builder: AcmeConnector.() -> Unit = {}
): AcmeConnector = AcmeConnector(accountManager, domain, keyStorePath, keyStorePassword, privateKeyPassword).apply(builder)

class AcmeConnector(
    private val accountManager: AcmeAccountManager,
    private val domain: String,
    override var keyStorePath: File,
    override var keyStorePassword: () -> CharArray,
    override var privateKeyPassword: () -> CharArray
) : EngineConnectorBuilder(ConnectorType.HTTPS), EngineSSLConnectorConfig {

    override val keyStore: KeyStore = when (keyStorePath.exists()) {
        true -> KeyStore.getInstance(keyStorePath, keyStorePassword())
        false -> KeyStore.getInstance("PKCS12").apply {
            load(null, null)
        }
    }
    override val keyAlias: String = domain
    override var trustStore: KeyStore? = null
    override var trustStorePath: File? = null
    override var port: Int = 443

    internal val certificate: X509Certificate?
        get() = keyStore.getCertificate(keyAlias) as? X509Certificate

    private val log = LoggerFactory.getLogger(javaClass)
    private val threadFactory: ThreadFactory = object : ThreadFactory {
        override fun newThread(r: Runnable): Thread = thread(start = false, isDaemon = true, name = javaClass.simpleName) { r.run() }
    }
    private val executor = Executors.newSingleThreadScheduledExecutor(threadFactory)
    private val sslReloader = JettySslReloader()
    private val selfSignedIssuerName = "CN=Ktor Acme Connector"
    private val selfSignedIssuer by lazy {
        CertificateUtils.createTestRootCertificate(
            selfSignedIssuerName,
            Instant.parse("2000-01-01T00:00:00Z"),
            Instant.parse("2100-01-01T00:00:00Z"),
            KeyPairUtils.createKeyPair(2048)
        )
    }

    val configure: JettyApplicationEngineBase.Configuration.() -> Unit = {
        configureServer = {
            addEventListener(sslReloader)
            addEventListener(JettyAnlpRegistrar)
            addLifeCycleListener(JettyStartedListener {
                executor.scheduleWithFixedDelay(this@AcmeConnector::orderCertificate, 0, 1, TimeUnit.HOURS)
            })
        }
    }

    private fun X509Certificate?.needsRefresh() =
        this == null || this.issuerX500Principal.name == selfSignedIssuerName || this.notAfter.toInstant().isBefore(Instant.now().minus(Duration.ofDays(7)))

    private fun orderCertificate() {
        try {
            val currentCertificate = certificate
            if (currentCertificate.needsRefresh()) {
                val account = accountManager.getOrCreateAccount()
                val order = account.newOrder().domain(domain).create()
                val authorization = order.authorizations.first { it.identifier.domain == domain }

                if (authorization.status == Status.PENDING) {
                    executeTlsChallenge(authorization)
                }

                order.update()

                if (order.status == Status.READY) {
                    val existingPrivateKey = keyStore.getKey(keyAlias, privateKeyPassword()) as? PrivateKey
                    val keyPair = if (currentCertificate != null && existingPrivateKey != null) {
                        KeyPair(currentCertificate.publicKey, existingPrivateKey)
                    } else {
                        KeyPairUtils.createKeyPair(2048)
                    }
                    val certificate = requestCertificate(order, keyPair) ?: throw IllegalStateException("Certificate is not available")

                    // add the requested certificate to the keystore
                    persistCertificateChain(keyPair.private, certificate.certificateChain)
                } else {
                    order.throwError()
                }
            }
        } catch (e: Exception) {
            log.error("Failed to order certificate, retrying in 1 minute", e)
            executor.schedule(this::orderCertificate, 1, TimeUnit.MINUTES)
        }
        if (certificate == null) {
            try {
                // Generate a self-signed certificate while waiting on ACME
                createSelfSignedCertificate()
            } catch (e: Exception) {
                log.error("Failed to generate self-signed certificate", e)
            }
        }
    }

    private fun executeTlsChallenge(authorization: Authorization) {
        val challenge = authorization.findChallenge(TlsAlpn01Challenge::class.java)
        if (challenge == null) {
            log.error("TLS ALPN 01 challenge is not available")
            return
        }

        try {
            configureTlsChallenge(authorization.identifier, challenge)
            challenge.trigger()
            runBlocking {
                while (authorization.status == Status.PENDING) {
                    delay(1000)
                    authorization.update()
                }
            }
        } finally {
            removeTlsChallenge(authorization.identifier)
        }
    }

    private fun configureTlsChallenge(identifier: Identifier, challenge: TlsAlpn01Challenge) {
        val alias = identifier.value + "-challenge"
        val keyPair = KeyPairUtils.createKeyPair(2048)
        val challengeCertificate = CertificateUtils.createTlsAlpn01Certificate(keyPair, identifier, challenge.acmeValidation)
        keyStore.setKeyEntry(alias, keyPair.private, privateKeyPassword(), arrayOf(challengeCertificate))
        sslReloader.reloadSslContextFactories()
    }

    private fun removeTlsChallenge(identifier: Identifier) {
        val alias = identifier.value + "-challenge"
        keyStore.deleteEntry(alias)
        sslReloader.reloadSslContextFactories()
    }

    private fun requestCertificate(order: Order, keyPair: KeyPair): Certificate? {
        val certificateSigningRequest = CSRBuilder().apply {
            addDomain(domain)
            sign(keyPair)
        }.encoded

        order.execute(certificateSigningRequest)
        runBlocking {
            while (order.status == Status.PENDING) {
                delay(1000)
                order.update()
            }
        }

        if (order.status == Status.VALID) {
            val newCertificate = order.certificate
            if (newCertificate != null) {
                return newCertificate
            }
        }

        order.throwError()
    }

    private fun createSelfSignedCertificate() {
        val keyPair = KeyPairUtils.createKeyPair(2048)
        val certificateSigningRequest = CSRBuilder().apply {
            addDomain(domain)
            sign(keyPair)
        }.encoded
        val certificate = CertificateUtils.createTestCertificate(
            PKCS10CertificationRequest(certificateSigningRequest),
            Instant.parse("2000-01-01T00:00:00Z"),
            Instant.parse("2100-01-01T00:00:00Z"),
            selfSignedIssuer,
            keyPair.private
        )

        // add the self-signed certificate to the keystore
        persistCertificateChain(keyPair.private, listOf(certificate, selfSignedIssuer))

        log.info("Generated self-signed certificate while waiting on ACME-issued certificate")
    }

    private fun persistCertificateChain(privateKey: PrivateKey, chain: List<X509Certificate>) {
        // add the certificate to the keystore
        keyStore.setKeyEntry(keyAlias, privateKey, privateKeyPassword(), chain.toTypedArray())

        // persist the updated keystore to disk
        keyStorePath.outputStream().use { keyStore.store(it, keyStorePassword()) }

        // reload the Jetty SSL context factories
        sslReloader.reloadSslContextFactories()
    }

    private fun Order.throwError(): Nothing {
        val error = error ?: throw IllegalStateException(status.name)
        throw IllegalStateException("${error.type}: ${error.detail}")
    }

}

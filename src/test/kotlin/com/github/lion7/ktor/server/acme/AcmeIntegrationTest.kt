package com.github.lion7.ktor.server.acme

import io.ktor.server.application.call
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.Jetty
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

internal class AcmeIntegrationTest {

    @Container
    private val pebbleChallengeTestServer: GenericContainer<*> = GenericContainer("letsencrypt/pebble-challtestsrv:latest")
        .withCommand("pebble-challtestsrv -tlsalpn01 \"\"")
        .withNetworkMode("host")

    @Container
    private val pebble: GenericContainer<*> = GenericContainer("letsencrypt/pebble")
        .withCommand("pebble -config /test/config/pebble-config.json -strict -dnsserver localhost:8053")
        .withNetworkMode("host")
        .withEnv("PEBBLE_VA_NOSLEEP", "1")

    @BeforeEach
    internal fun setUp() {
        pebbleChallengeTestServer.start()
        pebble.start()
    }

    @AfterEach
    internal fun tearDown() {
        pebbleChallengeTestServer.stop()
        pebble.stop()
    }

    @Test
    @DelicateCoroutinesApi
    fun acmeTest() {
        val acmeAccount = File.createTempFile("acme-account", ".pem").apply { delete(); deleteOnExit() }
        val acmeCerts = File.createTempFile("acme-certs", ".p12").apply { delete(); deleteOnExit() }
        val acmeConnector = acmeConnector(
            certificateAuthority = AcmeCertificateAuthorities.PEBBLE.url,
            accountKeyPairFile = acmeAccount,
            contact = "acme@example.com",
            agreeToTermsOfService = true,
            domains = listOf("localhost", "alt.localhost"),
            keyStorePath = acmeCerts,
            keyStorePassword = { "secret".toCharArray() },
            privateKeyPassword = { "secret".toCharArray() }
        ) {
            port = 5001
        }
        val server = GlobalScope.embeddedServer(factory = Jetty, connectors = arrayOf(acmeConnector), configure = acmeConnector.configure) {
            routing {
                get {
                    call.respond("Hello World!")
                }
            }
        }
        val engine = server.start(false)
        acmeConnector.waitForCertificate()
        try {
            val httpClient = HttpClient.newBuilder().sslContext(insecureContext()).build()
            val request = HttpRequest.newBuilder().GET().uri(URI("https://localhost:5001")).build()
            val response = httpClient.send(request, BodyHandlers.discarding())
            val serverCertificate = response.sslSession().orElseThrow().peerCertificates.first() as X509Certificate
            assertEquals(listOf("localhost", "alt.localhost"), serverCertificate.subjectAlternativeNames.flatMap { it.filterIsInstance<String>() })
            assertStartsWith("CN=Pebble Intermediate CA", serverCertificate.issuerX500Principal.name)
        } finally {
            engine.stop()
            acmeCerts.delete()
            acmeAccount.delete()
        }
    }

    private fun AcmeConnector.waitForCertificate() {
        for (i in 0..10) {
            if (certificate != null) return
            Thread.sleep(500)
        }
        throw IllegalStateException("ACME certificate is not present")
    }

    private fun assertStartsWith(prefix: String, actual: String) =
        assertTrue(actual.startsWith(prefix)) { "expected <$actual> to start with <$prefix>" }

    private fun insecureContext(): SSLContext {
        val insecureTrustManager = object : X509TrustManager {
            override fun checkClientTrusted(xcs: Array<X509Certificate?>?, string: String?) {}
            override fun checkServerTrusted(xcs: Array<X509Certificate?>?, string: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        val sslContext = SSLContext.getInstance("ssl")
        sslContext.init(null, arrayOf<TrustManager>(insecureTrustManager), null)
        return sslContext
    }

}

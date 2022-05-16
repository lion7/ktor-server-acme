package com.github.lion7.ktor.server.acme

import org.bouncycastle.asn1.x509.GeneralName
import org.shredzone.acme4j.Account
import org.shredzone.acme4j.AccountBuilder
import org.shredzone.acme4j.Identifier
import org.shredzone.acme4j.Session
import org.shredzone.acme4j.exception.AcmeServerException
import org.shredzone.acme4j.util.CertificateUtils
import org.shredzone.acme4j.util.KeyPairUtils
import java.io.File
import java.net.MalformedURLException
import java.net.URL
import java.security.KeyPair
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

class AcmeAccountManager(
    certificateAuthority: String,
    private val contact: String,
    private val agreeToTermsOfService: Boolean,
    private val keyStorePath: File,
    private val keyStorePassword: () -> CharArray,
    private val privateKeyPassword: () -> CharArray,
) {

    companion object {
        private const val acmeAccountAlias = "account"
    }

    private val session = Session(certificateAuthority)
    private val keyStore: KeyStore = when (keyStorePath.exists()) {
        true -> KeyStore.getInstance(keyStorePath, keyStorePassword())
        false -> KeyStore.getInstance("PKCS12").apply {
            load(null, null)
        }
    }

    fun getOrCreateAccount(): Account =
        findExistingAccount() ?: createNewAccount()

    private fun findExistingAccount(): Account? {
        val certificate = keyStore.getCertificate(acmeAccountAlias) as? X509Certificate ?: return null
        val privateKey = keyStore.getKey(acmeAccountAlias, privateKeyPassword()) as? PrivateKey ?: return null
        val keyPair = KeyPair(certificate.publicKey, privateKey)
        val location = certificate.subjectAlternativeNameAsURL()
        if (location != null) {
            return session.login(location, keyPair).account
        }
        return try {
            AccountBuilder().onlyExisting().useKeyPair(keyPair).create(session)
        } catch (e: AcmeServerException) {
            null
        }
    }

    private fun createNewAccount(): Account {
        val publicKey = keyStore.getCertificate(acmeAccountAlias)?.publicKey
        val privateKey = keyStore.getKey(acmeAccountAlias, privateKeyPassword()) as? PrivateKey
        val keyPair = if (publicKey != null && privateKey != null) KeyPair(publicKey, privateKey) else KeyPairUtils.createKeyPair(2048)
        val account = AccountBuilder().apply {
            when (contact.startsWith("mailto:")) {
                true -> addContact(contact)
                false -> addEmail(contact)
            }
            if (agreeToTermsOfService) {
                agreeToTermsOfService()
            }
            useKeyPair(keyPair)
        }.create(session)
        val certificate = CertificateUtils.createTlsAlpn01Certificate(keyPair, Identifier.dns(account.location.toString()), ByteArray(32))
        keyStore.setKeyEntry("account", keyPair.private, privateKeyPassword(), arrayOf(certificate))
        // persist the updated keystore to disk
        keyStorePath.outputStream().use { keyStore.store(it, keyStorePassword()) }
        return account
    }

    private fun X509Certificate.subjectAlternativeNameAsURL(): URL? =
        subjectAlternativeNames
            .filter { it.getOrNull(0) == GeneralName.dNSName }
            .mapNotNull { it.getOrNull(1) as? String }
            .mapNotNull {
                try {
                    URL(it)
                } catch (e: MalformedURLException) {
                    null
                }
            }.singleOrNull()

}

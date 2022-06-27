package com.github.lion7.ktor.server.acme

import org.shredzone.acme4j.Account
import org.shredzone.acme4j.AccountBuilder
import org.shredzone.acme4j.Session
import org.shredzone.acme4j.exception.AcmeServerException
import org.shredzone.acme4j.util.KeyPairUtils
import java.io.File
import java.net.Proxy
import java.security.KeyPair

class AcmeAccountManager(
    certificateAuthority: String,
    keyPairFile: File,
    private val contact: String,
    private val agreeToTermsOfService: Boolean
) {

    private val session = Session(certificateAuthority)
    private val keyPair: KeyPair = when (keyPairFile.exists()) {
        true -> keyPairFile.reader().use(KeyPairUtils::readKeyPair)
        false -> KeyPairUtils.createKeyPair(2048).also { keyPair -> keyPairFile.writer().use { KeyPairUtils.writeKeyPair(keyPair, it) } }
    }

    fun setProxy(proxy: Proxy) {
        session.networkSettings().proxy = proxy
    }

    fun getOrCreateAccount(): Account =
        findExistingAccount() ?: createNewAccount()

    private fun findExistingAccount(): Account? = try {
        AccountBuilder().onlyExisting().useKeyPair(keyPair).create(session)
    } catch (e: AcmeServerException) {
        null
    }

    private fun createNewAccount(): Account = AccountBuilder().apply {
        when (contact.startsWith("mailto:")) {
            true -> addContact(contact)
            false -> addEmail(contact)
        }
        if (agreeToTermsOfService) {
            agreeToTermsOfService()
        }
        useKeyPair(keyPair)
    }.create(session)

}

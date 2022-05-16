package com.github.lion7.ktor.server.acme

import io.ktor.server.testing.*
import java.io.File
import kotlin.test.Test

internal class AcmeTest {

    @Test
    fun acmeTest() = testApplication {
        environment {
            val password: () -> CharArray = { "secret".toCharArray() }
            val accountManager = AcmeAccountManager(AcmeCertificateAuthorities.PEBBLE.url, "pebble@example.com", File("acme-account.p12"), password, password)
            connectors += acmeConnector(accountManager, "example.com", File("acme-certs.p12"), password, password) {}
        }
    }
}

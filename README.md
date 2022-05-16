# ACME connector for Ktor Server 2.0

[![](https://jitpack.io/v/lion7/ktor-server-acme.svg)](https://jitpack.io/#lion7/ktor-server-acme)

## Example usage

```kotlin
package com.github.lion7.ktor.server.acme

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.jetty.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import java.io.File

object JettyExample {

    @DelicateCoroutinesApi
    @JvmStatic
    fun main(args: Array<String>) {
        val password: () -> CharArray = { "secret".toCharArray() }
        val acmeAccountManager = AcmeAccountManager(
            certificateAuthority = AcmeCertificateAuthorities.LETSENCRYPT_STAGING.url,
            contact = "acme@example.com",
            agreeToTermsOfService = true,
            keyStorePath = File("acme-account.p12"),
            keyStorePassword = password,
            privateKeyPassword = password
        )
        val acmeConnector = acmeConnector(
            accountManager = acmeAccountManager,
            domain = "example.com",
            keyStorePath = File("acme-certs.p12"),
            keyStorePassword = password,
            privateKeyPassword = password
        )
        val server = GlobalScope.embeddedServer(factory = Jetty, connectors = arrayOf(acmeConnector), configure = acmeConnector.configure) {
            routing {
                get {
                    call.respond("Hello World!")
                }
            }
        }
        server.start(true)
    }

}
```

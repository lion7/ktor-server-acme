package com.github.lion7.ktor.server.acme

enum class AcmeCertificateAuthorities(val url: String) {
    PEBBLE("acme://pebble"),
    LETSENCRYPT_STAGING("acme://letsencrypt.org/staging"),
    LETSENCRYPT_PRODUCTION("acme://letsencrypt.org")
}

package com.bara.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.util.Base64

@ConfigurationProperties(prefix = "bara.auth.jwt")
data class JwtProperties(
    val issuer: String,
    val audience: String,
    val expirySeconds: Long,
    val privateKeyBase64: String,
    val publicKeyBase64: String,
) {
    fun privateKeyPem(): String = String(Base64.getDecoder().decode(privateKeyBase64))
    fun publicKeyPem(): String = String(Base64.getDecoder().decode(publicKeyBase64))
}

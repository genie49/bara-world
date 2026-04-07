package com.bara.auth.adapter.out.external

import com.bara.auth.application.port.out.ApiKeyGenerator
import com.bara.auth.application.port.out.GeneratedApiKey
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom

@Component
class ApiKeyGeneratorAdapter : ApiKeyGenerator {
    private val secureRandom = SecureRandom()

    override fun generate(): GeneratedApiKey {
        val randomHex = ByteArray(32).also { secureRandom.nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val rawKey = "bk_$randomHex"
        val keyHash = sha256(rawKey)
        val keyPrefix = rawKey.substring(0, 10)
        return GeneratedApiKey(rawKey = rawKey, keyHash = keyHash, keyPrefix = keyPrefix)
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

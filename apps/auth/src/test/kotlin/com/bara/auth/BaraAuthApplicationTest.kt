package com.bara.auth

import com.bara.auth.adapter.out.persistence.ApiKeyMongoDataRepository
import com.bara.auth.adapter.out.persistence.ProviderMongoDataRepository
import com.bara.auth.adapter.out.persistence.UserMongoDataRepository
import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.TestPropertySource
import java.security.KeyPairGenerator
import java.util.Base64

@SpringBootTest
@TestPropertySource(
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        "bara.auth.google.client-id=test-client",
        "bara.auth.google.client-secret=test-secret",
        "bara.auth.google.redirect-uri=http://localhost:5173/auth/google/callback",
    ]
)
class BaraAuthApplicationTest {

    @MockkBean
    lateinit var userMongoDataRepository: UserMongoDataRepository

    @MockkBean
    lateinit var providerMongoDataRepository: ProviderMongoDataRepository

    @MockkBean
    lateinit var apiKeyMongoDataRepository: ApiKeyMongoDataRepository

    @MockkBean
    lateinit var stringRedisTemplate: StringRedisTemplate

    @Test
    fun contextLoads() {
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun jwtKeyProperties(registry: DynamicPropertyRegistry) {
            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val privPem = toPem("PRIVATE KEY", kp.private.encoded)
            val pubPem = toPem("PUBLIC KEY", kp.public.encoded)
            registry.add("bara.auth.jwt.private-key-base64") {
                Base64.getEncoder().encodeToString(privPem.toByteArray())
            }
            registry.add("bara.auth.jwt.public-key-base64") {
                Base64.getEncoder().encodeToString(pubPem.toByteArray())
            }
        }

        private fun toPem(type: String, bytes: ByteArray): String {
            val b64 = Base64.getEncoder().encodeToString(bytes)
            val lines = b64.chunked(64).joinToString("\n")
            return "-----BEGIN $type-----\n$lines\n-----END $type-----\n"
        }
    }
}

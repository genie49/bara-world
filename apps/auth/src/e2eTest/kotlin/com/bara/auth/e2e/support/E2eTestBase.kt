package com.bara.auth.e2e.support

import com.bara.test.DatabaseCleaner
import com.bara.test.MongoContainerSupport
import com.bara.test.RedisContainerSupport
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.security.KeyPairGenerator
import java.util.Base64

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("e2e")
abstract class E2eTestBase {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var mongoTemplate: MongoTemplate

    @BeforeEach
    open fun cleanDatabase() {
        DatabaseCleaner.clean(mongoTemplate)
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun containerProperties(registry: DynamicPropertyRegistry) {
            MongoContainerSupport.register(registry, dbName = "bara-auth-e2e")
            RedisContainerSupport.register(registry)

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

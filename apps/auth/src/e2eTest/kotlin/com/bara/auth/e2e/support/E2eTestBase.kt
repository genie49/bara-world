package com.bara.auth.e2e.support

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
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
    fun cleanDatabase() {
        mongoTemplate.db.listCollectionNames().forEach { name ->
            mongoTemplate.db.getCollection(name).deleteMany(org.bson.Document())
        }
    }

    companion object {
        @JvmStatic
        val mongo: GenericContainer<*> = GenericContainer("mongo:7")
            .withExposedPorts(27017)

        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:7-alpine")
            .withExposedPorts(6379)

        init {
            mongo.start()
            redis.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun containerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.mongodb.uri") {
                "mongodb://${mongo.host}:${mongo.getMappedPort(27017)}/bara-auth-e2e"
            }
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }

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

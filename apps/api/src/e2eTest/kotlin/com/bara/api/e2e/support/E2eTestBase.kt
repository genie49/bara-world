package com.bara.api.e2e.support

import com.bara.test.DatabaseCleaner
import com.bara.test.MongoContainerSupport
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

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
            MongoContainerSupport.register(registry, dbName = "bara-api-e2e")
        }
    }
}

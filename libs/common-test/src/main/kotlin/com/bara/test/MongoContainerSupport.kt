package com.bara.test

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.GenericContainer

object MongoContainerSupport {

    private val container: GenericContainer<*> = GenericContainer("mongo:7")
        .withExposedPorts(27017)

    init {
        container.start()
    }

    fun register(registry: DynamicPropertyRegistry, dbName: String) {
        registry.add("spring.data.mongodb.uri") {
            "mongodb://${container.host}:${container.getMappedPort(27017)}/$dbName"
        }
    }
}

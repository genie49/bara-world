package com.bara.test

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.GenericContainer

object RedisContainerSupport {

    private val container: GenericContainer<*> = GenericContainer("redis:7-alpine")
        .withExposedPorts(6379)

    init {
        container.start()
    }

    fun register(registry: DynamicPropertyRegistry) {
        registry.add("spring.data.redis.host") { container.host }
        registry.add("spring.data.redis.port") { container.getMappedPort(6379) }
    }
}

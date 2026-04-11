package com.bara.test

import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.containers.KafkaContainer
import org.testcontainers.utility.DockerImageName

object KafkaContainerSupport {

    private val container: KafkaContainer = KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.5.3"),
    )

    init {
        container.start()
    }

    val bootstrapServers: String
        get() = container.bootstrapServers

    fun register(registry: DynamicPropertyRegistry) {
        registry.add("spring.kafka.bootstrap-servers") { container.bootstrapServers }
    }
}

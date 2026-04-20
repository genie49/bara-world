package com.bara.api.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest(classes = [TaskPropertiesTest.Config::class])
@TestPropertySource(
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration," +
            "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
        "bara.api.task.block-timeout-seconds=10",
        "bara.api.task.kafka-publish-timeout-seconds=3",
        "bara.api.task.stream-grace-period-seconds=90",
        "bara.api.task.mongo-ttl-days=14",
        "bara.api.task.emitter-timeout-ms=600000",
        "bara.api.task.heartbeat-interval-ms=10000",
    ]
)
class TaskPropertiesTest {

    @SpringBootApplication
    @EnableConfigurationProperties(TaskProperties::class)
    class Config

    @Autowired
    lateinit var properties: TaskProperties

    @Test
    fun `binds all task properties including SSE fields`() {
        assertThat(properties.blockTimeoutSeconds).isEqualTo(10)
        assertThat(properties.kafkaPublishTimeoutSeconds).isEqualTo(3)
        assertThat(properties.streamGracePeriodSeconds).isEqualTo(90)
        assertThat(properties.mongoTtlDays).isEqualTo(14)
        assertThat(properties.emitterTimeoutMs).isEqualTo(600_000)
        assertThat(properties.heartbeatIntervalMs).isEqualTo(10_000)
    }
}

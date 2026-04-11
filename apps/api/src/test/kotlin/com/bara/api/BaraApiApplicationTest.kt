package com.bara.api

import com.bara.api.adapter.`in`.kafka.ResultConsumerAdapter
import com.bara.api.adapter.out.persistence.TaskMongoDataRepository
import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.application.port.out.TaskEventBusPort
import com.bara.api.application.port.out.TaskPublisherPort
import com.bara.api.application.port.out.TaskRepositoryPort
import com.ninjasquad.springmockk.MockkBean
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest
@TestPropertySource(
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration," +
            "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
    ]
)
class BaraApiApplicationTest {

    @MockkBean
    lateinit var agentRepository: AgentRepository

    @MockkBean
    lateinit var agentRegistryPort: AgentRegistryPort

    @MockkBean
    lateinit var taskPublisherPort: TaskPublisherPort

    @MockkBean
    lateinit var taskRepositoryPort: TaskRepositoryPort

    @MockkBean
    lateinit var taskEventBusPort: TaskEventBusPort

    @MockkBean
    lateinit var taskMongoDataRepository: TaskMongoDataRepository

    @MockkBean
    lateinit var resultConsumerAdapter: ResultConsumerAdapter

    @Test
    fun contextLoads() {
    }
}

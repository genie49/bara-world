package com.bara.api.adapter.out.kafka

import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate

class TaskKafkaPublisherTest {

    private val kafkaTemplate = mockk<KafkaTemplate<String, String>>(relaxed = true)
    private val publisher = TaskKafkaPublisher(kafkaTemplate)

    @Test
    fun `publish는 tasks dot agentId 토픽에 JSON을 발행한다`() {
        val message = mapOf(
            "task_id" to "task-1",
            "user_id" to "user-1",
            "message" to mapOf("role" to "user", "parts" to listOf(mapOf("text" to "hi"))),
        )

        publisher.publish("agent-001", message)

        verify { kafkaTemplate.send(eq("tasks.agent-001"), any<String>()) }
    }
}

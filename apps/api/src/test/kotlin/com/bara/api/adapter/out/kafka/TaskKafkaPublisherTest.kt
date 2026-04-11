package com.bara.api.adapter.out.kafka

import com.bara.api.application.port.out.TaskMessagePayload
import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.A2APart
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.util.concurrent.CompletableFuture
import kotlin.test.assertTrue

class TaskKafkaPublisherTest {

    private val kafkaTemplate = mockk<KafkaTemplate<String, String>>()
    private val publisher = TaskKafkaPublisher(kafkaTemplate, ObjectMapper())

    @Test
    fun `publish는 tasks dot agentId 토픽에 JSON을 발행하고 ack를 대기한다`() {
        val topicSlot = slot<String>()
        val valueSlot = slot<String>()
        every { kafkaTemplate.send(capture(topicSlot), capture(valueSlot)) } returns
            CompletableFuture.completedFuture(mockk<SendResult<String, String>>(relaxed = true))

        val payload = TaskMessagePayload(
            taskId = "t-1",
            contextId = "c-1",
            userId = "u-1",
            requestId = "r-1",
            resultTopic = "results.api",
            allowedAgents = emptyList(),
            message = A2AMessage(
                messageId = "m-1",
                role = "user",
                parts = listOf(A2APart(kind = "text", text = "hi")),
            ),
        )

        publisher.publish("agent-001", payload)

        verify { kafkaTemplate.send("tasks.agent-001", any()) }
        assertTrue(valueSlot.captured.contains("\"task_id\":\"t-1\""))
        assertTrue(valueSlot.captured.contains("\"parts\":[{\"kind\":\"text\",\"text\":\"hi\"}]"))
    }
}

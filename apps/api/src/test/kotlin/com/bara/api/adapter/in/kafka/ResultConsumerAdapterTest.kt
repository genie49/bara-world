package com.bara.api.adapter.`in`.kafka

import com.bara.api.application.port.out.TaskEventBusPort
import com.bara.api.application.port.out.TaskRepositoryPort
import com.bara.api.config.TaskProperties
import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.A2APart
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskState
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.kafka.support.Acknowledgment
import java.time.Instant

class ResultConsumerAdapterTest {

    private val repository = mockk<TaskRepositoryPort>()
    private val eventBus = mockk<TaskEventBusPort>()
    private val properties = TaskProperties()
    private val mapper = ObjectMapper().registerKotlinModule()
    private val ack = mockk<Acknowledgment>(relaxed = true)

    private val adapter = ResultConsumerAdapter(repository, eventBus, mapper, properties)

    private val now = Instant.parse("2026-04-11T00:00:00Z")

    private val storedTask = Task(
        id = "t-1",
        agentId = "a-1",
        agentName = "my-agent",
        userId = "u-1",
        contextId = "c-1",
        state = TaskState.SUBMITTED,
        inputMessage = A2AMessage("m-1", "user", listOf(A2APart("text", "hi"))),
        requestId = "r-1",
        createdAt = now,
        updatedAt = now,
        expiredAt = now.plusSeconds(7 * 24 * 3600),
    )

    @Test
    fun `completed 결과 수신 시 Mongo update 후 EventBus publish 후 ack commit`() {
        val payload = """
            {
              "taskId":"t-1","contextId":"c-1","state":"completed",
              "statusMessage":{"messageId":"m-2","role":"agent","parts":[{"kind":"text","text":"done"}]},
              "artifact":null,"errorCode":null,"errorMessage":null,"final":true,
              "timestamp":"2026-04-11T00:00:01Z"
            }
        """.trimIndent()

        every { repository.findById("t-1") } returns storedTask
        every {
            repository.updateState(
                id = "t-1",
                state = TaskState.COMPLETED,
                statusMessage = any(),
                artifacts = emptyList(),
                errorCode = null,
                errorMessage = null,
                updatedAt = any(),
                completedAt = any(),
                expiredAt = any(),
            )
        } returns true
        every { eventBus.publish(eq("t-1"), any()) } returns "1-0"
        justRun { eventBus.close("t-1") }

        adapter.onMessage(payload, ack)

        verify {
            repository.updateState(
                id = "t-1",
                state = TaskState.COMPLETED,
                statusMessage = any(),
                artifacts = emptyList(),
                errorCode = null,
                errorMessage = null,
                updatedAt = any(),
                completedAt = any(),
                expiredAt = any(),
            )
            eventBus.publish("t-1", any())
            eventBus.close("t-1")
            ack.acknowledge()
        }
    }

    @Test
    fun `taskId가 Mongo에 없으면 skip 후 ack commit`() {
        val payload = """
            {"taskId":"missing","contextId":"c","state":"completed","final":true,"timestamp":"2026-04-11T00:00:00Z"}
        """.trimIndent()
        every { repository.findById("missing") } returns null

        adapter.onMessage(payload, ack)

        verify(exactly = 0) { eventBus.publish(any(), any()) }
        verify { ack.acknowledge() }
    }

    @Test
    fun `unsupported state input-required는 failed로 변환 기록`() {
        val payload = """
            {"taskId":"t-1","contextId":"c-1","state":"input-required","final":false,"timestamp":"2026-04-11T00:00:00Z"}
        """.trimIndent()
        every { repository.findById("t-1") } returns storedTask
        val stateSlot = slot<TaskState>()
        every {
            repository.updateState(
                id = "t-1", state = capture(stateSlot),
                statusMessage = any(), artifacts = any(),
                errorCode = "unsupported-state", errorMessage = any(),
                updatedAt = any(), completedAt = any(), expiredAt = any(),
            )
        } returns true
        every { eventBus.publish(eq("t-1"), any()) } returns "1-0"
        justRun { eventBus.close("t-1") }

        adapter.onMessage(payload, ack)

        assert(stateSlot.captured == TaskState.FAILED)
        verify { ack.acknowledge() }
    }
}

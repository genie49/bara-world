package com.bara.api.application.service.command

import com.bara.api.application.port.`in`.command.SendMessageUseCase
import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.TaskEventBusPort
import com.bara.api.application.port.out.TaskMessagePayload
import com.bara.api.application.port.out.TaskPublisherPort
import com.bara.api.application.port.out.TaskRepositoryPort
import com.bara.api.config.TaskProperties
import com.bara.api.domain.exception.AgentTimeoutException
import com.bara.api.domain.exception.AgentUnavailableException
import com.bara.api.domain.exception.KafkaPublishException
import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.A2APart
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskEvent
import com.bara.api.domain.model.TaskState
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SendMessageServiceTest {

    private val agentRegistryPort = mockk<AgentRegistryPort>()
    private val taskPublisherPort = mockk<TaskPublisherPort>()
    private val taskRepositoryPort = mockk<TaskRepositoryPort>()
    private val taskEventBusPort = mockk<TaskEventBusPort>()
    private val properties = TaskProperties(blockTimeoutSeconds = 2)

    private val service = SendMessageService(
        agentRegistryPort = agentRegistryPort,
        taskPublisherPort = taskPublisherPort,
        taskRepositoryPort = taskRepositoryPort,
        taskEventBusPort = taskEventBusPort,
        properties = properties,
    )

    private val request = SendMessageUseCase.SendMessageRequest(
        messageId = "m-1", text = "안녕", contextId = "c-1",
    )

    private fun storedTask(state: TaskState = TaskState.SUBMITTED): Task {
        val now = Instant.now()
        return Task(
            id = "t-1",
            agentId = "agent-001",
            agentName = "my-agent",
            userId = "user-1",
            contextId = "c-1",
            state = state,
            inputMessage = A2AMessage("m-1", "user", listOf(A2APart("text", "안녕"))),
            requestId = "r-1",
            createdAt = now,
            updatedAt = now,
            expiredAt = now.plusSeconds(7 * 24 * 3600),
        )
    }

    @Test
    fun `정상 블로킹 플로우 — completed 이벤트를 받으면 A2ATaskDto를 반환한다`() {
        every { agentRegistryPort.getAgentId("my-agent") } returns "agent-001"
        val taskSlot = slot<Task>()
        every { taskRepositoryPort.save(capture(taskSlot)) } answers { taskSlot.captured }
        every { taskRepositoryPort.findById(any()) } answers { taskSlot.captured }
        every { taskEventBusPort.publish(any(), any()) } returns "1-0"
        justRun { taskPublisherPort.publish("agent-001", any<TaskMessagePayload>()) }

        val completedEvent = TaskEvent(
            taskId = "_will_be_overridden",
            contextId = "c-1",
            state = TaskState.COMPLETED,
            statusMessage = A2AMessage("m-2", "agent", listOf(A2APart("text", "done"))),
            final = true,
            timestamp = Instant.now(),
        )
        every { taskEventBusPort.await(any(), any()) } answers {
            val tid = firstArg<String>()
            CompletableFuture.completedFuture(completedEvent.copy(taskId = tid))
        }

        val future = service.sendBlocking("user-1", "my-agent", request)
        val dto = future.get(1, TimeUnit.SECONDS)

        assertEquals("completed", dto.status.state)
        verify { taskRepositoryPort.save(any<Task>()) }
        verify { taskEventBusPort.publish(any(), any()) }      // submitted
        verify { taskPublisherPort.publish("agent-001", any<TaskMessagePayload>()) }
        verify { taskEventBusPort.await(any(), any()) }
    }

    @Test
    fun `Agent가 레지스트리에 없으면 AgentUnavailable 예외`() {
        every { agentRegistryPort.getAgentId("dead") } returns null

        val ex = runCatching {
            service.sendBlocking("user-1", "dead", request).get()
        }.exceptionOrNull()
        assertTrue(ex?.cause is AgentUnavailableException || ex is AgentUnavailableException)
    }

    @Test
    fun `Kafka publish 실패 시 Task를 failed 로 전환하고 KafkaPublishException 전파`() {
        every { agentRegistryPort.getAgentId("my-agent") } returns "agent-001"
        val taskSlot = slot<Task>()
        every { taskRepositoryPort.save(capture(taskSlot)) } answers { taskSlot.captured }
        every { taskEventBusPort.publish(any(), any()) } returns "1-0"
        every { taskEventBusPort.await(any(), any()) } returns CompletableFuture<TaskEvent>()
        every { taskPublisherPort.publish("agent-001", any<TaskMessagePayload>()) } throws
            KafkaPublishException("broker down")
        every {
            taskRepositoryPort.updateState(
                id = any(), state = TaskState.FAILED,
                statusMessage = any(), artifacts = any(),
                errorCode = any(), errorMessage = any(),
                updatedAt = any(), completedAt = any(), expiredAt = any(),
            )
        } returns true

        val ex = runCatching {
            service.sendBlocking("user-1", "my-agent", request).get(1, TimeUnit.SECONDS)
        }.exceptionOrNull()
        assertTrue(ex is KafkaPublishException, "Expected KafkaPublishException but was $ex")
        verify {
            taskRepositoryPort.updateState(
                id = any(), state = TaskState.FAILED,
                statusMessage = any(), artifacts = any(),
                errorCode = "kafka-publish-failed", errorMessage = any(),
                updatedAt = any(), completedAt = any(), expiredAt = any(),
            )
        }
    }

    @Test
    fun `await 타임아웃이면 AgentTimeoutException 전파`() {
        every { agentRegistryPort.getAgentId("my-agent") } returns "agent-001"
        val taskSlot = slot<Task>()
        every { taskRepositoryPort.save(capture(taskSlot)) } answers { taskSlot.captured }
        every { taskEventBusPort.publish(any(), any()) } returns "1-0"
        justRun { taskPublisherPort.publish("agent-001", any<TaskMessagePayload>()) }
        every { taskEventBusPort.await(any(), any()) } answers {
            val f = CompletableFuture<TaskEvent>()
            f.completeExceptionally(TimeoutException("await"))
            f
        }

        val future = service.sendBlocking("user-1", "my-agent", request)
        val ex = runCatching { future.get(1, TimeUnit.SECONDS) }.exceptionOrNull()
        assertTrue((ex as? ExecutionException)?.cause is AgentTimeoutException)
    }
}

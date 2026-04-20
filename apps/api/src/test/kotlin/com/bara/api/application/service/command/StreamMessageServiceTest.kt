package com.bara.api.application.service.command

import com.bara.api.adapter.`in`.rest.sse.SseBridge
import com.bara.api.application.port.`in`.command.SendMessageUseCase
import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.Subscription
import com.bara.api.application.port.out.TaskEventBusPort
import com.bara.api.application.port.out.TaskMessagePayload
import com.bara.api.application.port.out.TaskPublisherPort
import com.bara.api.application.port.out.TaskRepositoryPort
import com.bara.api.config.TaskProperties
import com.bara.api.domain.exception.AgentUnavailableException
import com.bara.api.adapter.`in`.rest.a2a.A2ATaskDto
import com.bara.api.domain.exception.KafkaPublishException
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskEvent
import com.bara.api.domain.model.TaskState
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StreamMessageServiceTest {

    private val agentRegistryPort = mockk<AgentRegistryPort>()
    private val taskPublisherPort = mockk<TaskPublisherPort>()
    private val taskRepositoryPort = mockk<TaskRepositoryPort>()
    private val taskEventBusPort = mockk<TaskEventBusPort>()
    private val sseBridge = mockk<SseBridge>(relaxed = true)
    private val properties = TaskProperties()

    private val service = StreamMessageService(
        agentRegistryPort = agentRegistryPort,
        taskPublisherPort = taskPublisherPort,
        taskRepositoryPort = taskRepositoryPort,
        taskEventBusPort = taskEventBusPort,
        sseBridge = sseBridge,
        properties = properties,
    )

    private val request = SendMessageUseCase.SendMessageRequest(
        messageId = "m-1", text = "hello", contextId = "c-1",
    )

    @Test
    fun `정상 플로우 — emitter 를 반환하고 호출 순서 - save → publish submitted → subscribe → attach → kafka publish`() {
        every { agentRegistryPort.getAgentId("my-agent") } returns "agent-001"
        val taskSlot = slot<Task>()
        every { taskRepositoryPort.save(capture(taskSlot)) } answers { taskSlot.captured }
        val publishedEventSlot = slot<TaskEvent>()
        every { taskEventBusPort.publish(any(), capture(publishedEventSlot)) } returns "1-0"
        val subscription = mockk<Subscription>(relaxed = true)
        every {
            taskEventBusPort.subscribe(any(), "0", any())
        } returns subscription
        justRun { taskPublisherPort.publish("agent-001", any<TaskMessagePayload>()) }

        val emitter = service.stream("user-1", "my-agent", null, request)

        assertNotNull(emitter)

        verifyOrder {
            agentRegistryPort.getAgentId("my-agent")
            taskRepositoryPort.save(any<Task>())
            taskEventBusPort.publish(any(), any<TaskEvent>())
            taskEventBusPort.subscribe(any(), "0", any())
            sseBridge.attach(any(), null, emitter, subscription)
            taskPublisherPort.publish("agent-001", any<TaskMessagePayload>())
        }

        // SUBMITTED task 가 저장되고 그 taskId 로 subscribe
        assertTrue(taskSlot.captured.state == TaskState.SUBMITTED)
        assertTrue(publishedEventSlot.captured.state == TaskState.SUBMITTED)
    }

    @Test
    fun `Agent 가 레지스트리에 없으면 AgentUnavailableException - 부수효과 없음`() {
        every { agentRegistryPort.getAgentId("dead") } returns null

        val ex = runCatching {
            service.stream("user-1", "dead", null, request)
        }.exceptionOrNull()
        assertTrue(ex is AgentUnavailableException)

        verify(exactly = 0) { taskRepositoryPort.save(any()) }
        verify(exactly = 0) { taskEventBusPort.publish(any(), any()) }
        verify(exactly = 0) { taskEventBusPort.subscribe(any(), any(), any()) }
        verify(exactly = 0) { sseBridge.attach(any(), any(), any(), any()) }
        verify(exactly = 0) { taskPublisherPort.publish(any(), any()) }
    }

    @Test
    fun `Kafka publish 실패 시 Task 를 failed 로 전환하고 final 이벤트 발행 후 KafkaPublishException 전파`() {
        every { agentRegistryPort.getAgentId("my-agent") } returns "agent-001"
        val taskSlot = slot<Task>()
        every { taskRepositoryPort.save(capture(taskSlot)) } answers { taskSlot.captured }
        every { taskEventBusPort.publish(any(), any()) } returns "1-0"
        val subscription = mockk<Subscription>(relaxed = true)
        every { taskEventBusPort.subscribe(any(), "0", any()) } returns subscription
        every {
            taskRepositoryPort.updateState(
                id = any(), state = TaskState.FAILED,
                statusMessage = null, artifacts = emptyList(),
                errorCode = "kafka-publish-failed", errorMessage = any(),
                updatedAt = any(), completedAt = any(), expiredAt = any(),
            )
        } returns true
        every {
            taskPublisherPort.publish("agent-001", any<TaskMessagePayload>())
        } throws KafkaPublishException("broker down")

        val ex = runCatching {
            service.stream("user-1", "my-agent", null, request)
        }.exceptionOrNull()
        assertTrue(ex is KafkaPublishException, "Expected KafkaPublishException but was $ex")

        verify(exactly = 1) {
            taskRepositoryPort.updateState(
                id = any(), state = TaskState.FAILED,
                statusMessage = null, artifacts = emptyList(),
                errorCode = "kafka-publish-failed", errorMessage = any(),
                updatedAt = any(), completedAt = any(), expiredAt = any(),
            )
        }
        // failed terminal event 발행
        verify {
            taskEventBusPort.publish(
                any(),
                match<TaskEvent> { it.state == TaskState.FAILED && it.final },
            )
        }
    }

    @Test
    fun `subscribe listener 가 TaskEvent 를 받으면 SseBridge send 로 전달 (final flag 포함)`() {
        every { agentRegistryPort.getAgentId("my-agent") } returns "agent-001"
        val taskSlot = slot<Task>()
        every { taskRepositoryPort.save(capture(taskSlot)) } answers { taskSlot.captured }
        every { taskEventBusPort.publish(any(), any()) } returns "1-0"
        val subscription = mockk<Subscription>(relaxed = true)
        val listenerSlot = slot<(String, TaskEvent) -> Unit>()
        every {
            taskEventBusPort.subscribe(any(), "0", capture(listenerSlot))
        } returns subscription
        justRun { taskPublisherPort.publish("agent-001", any<TaskMessagePayload>()) }

        service.stream("user-1", "my-agent", null, request)

        val capturedTaskId = taskSlot.captured.id
        val capturedContextId = taskSlot.captured.contextId

        // 캡처된 listener 를 synthetic entryId + TaskEvent 로 직접 호출하여
        // SseBridge.send(taskId, entryId, dto, final) 로 포워딩되는지 검증한다.
        val finalEvent = TaskEvent(
            taskId = capturedTaskId,
            contextId = capturedContextId,
            state = TaskState.COMPLETED,
            statusMessage = null,
            artifact = null,
            errorCode = null,
            errorMessage = null,
            final = true,
            timestamp = Instant.now(),
        )
        listenerSlot.captured.invoke("1712665200000-0", finalEvent)

        verify(exactly = 1) {
            sseBridge.send(capturedTaskId, "1712665200000-0", any<A2ATaskDto>(), true)
        }
    }

    @Test
    fun `Kafka publish 실패 시 emitter 를 completeWithError 로 종료한다 (bridge release trigger)`() {
        every { agentRegistryPort.getAgentId("my-agent") } returns "agent-001"
        val taskSlot = slot<Task>()
        every { taskRepositoryPort.save(capture(taskSlot)) } answers { taskSlot.captured }
        every { taskEventBusPort.publish(any(), any()) } returns "1-0"
        val subscription = mockk<Subscription>(relaxed = true)
        every { taskEventBusPort.subscribe(any(), "0", any()) } returns subscription
        every {
            taskRepositoryPort.updateState(
                id = any(), state = TaskState.FAILED,
                statusMessage = null, artifacts = emptyList(),
                errorCode = "kafka-publish-failed", errorMessage = any(),
                updatedAt = any(), completedAt = any(), expiredAt = any(),
            )
        } returns true
        val emitterSlot = slot<SseEmitter>()
        justRun { sseBridge.attach(any(), any(), capture(emitterSlot), any()) }
        every {
            taskPublisherPort.publish("agent-001", any<TaskMessagePayload>())
        } throws KafkaPublishException("broker down")

        val ex = runCatching {
            service.stream("user-1", "my-agent", null, request)
        }.exceptionOrNull()
        assertTrue(ex is KafkaPublishException, "Expected KafkaPublishException but was $ex")

        // completeWithError 이후에는 emitter 가 이미 종료 상태이므로 send 시 IllegalStateException 이 발생한다.
        assertTrue(emitterSlot.isCaptured, "Emitter should have been captured via sseBridge.attach")
        assertFailsWith<IllegalStateException> {
            emitterSlot.captured.send(SseEmitter.event().comment("post-check"))
        }
    }
}

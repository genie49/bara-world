package com.bara.api.application.service.query

import com.bara.api.adapter.`in`.rest.a2a.A2ATaskDto
import com.bara.api.adapter.`in`.rest.sse.SseBridge
import com.bara.api.application.port.out.Subscription
import com.bara.api.application.port.out.TaskEventBusPort
import com.bara.api.application.port.out.TaskRepositoryPort
import com.bara.api.config.TaskProperties
import com.bara.api.domain.exception.StreamUnsupportedException
import com.bara.api.domain.exception.TaskAccessDeniedException
import com.bara.api.domain.exception.TaskNotFoundException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class SubscribeTaskServiceTest {

    private val taskRepositoryPort = mockk<TaskRepositoryPort>()
    private val taskEventBusPort = mockk<TaskEventBusPort>()
    private val sseBridge = mockk<SseBridge>(relaxed = true)
    private val properties = TaskProperties()

    private val service = SubscribeTaskService(
        taskRepositoryPort = taskRepositoryPort,
        taskEventBusPort = taskEventBusPort,
        sseBridge = sseBridge,
        properties = properties,
    )

    private val now: Instant = Instant.parse("2026-04-20T00:00:00Z")

    private fun task(
        id: String = "t-1",
        userId: String = "user-1",
        state: TaskState = TaskState.WORKING,
    ): Task = Task(
        id = id,
        agentId = "agent-001",
        agentName = "my-agent",
        userId = userId,
        contextId = "c-1",
        state = state,
        inputMessage = A2AMessage("m-1", "user", listOf(A2APart("text", "hi"))),
        requestId = "r-1",
        createdAt = now,
        updatedAt = now,
        expiredAt = now.plusSeconds(7 * 24 * 3600),
    )

    @Test
    fun `존재하지 않는 task 면 TaskNotFoundException`() {
        every { taskRepositoryPort.findById("t-missing") } returns null

        assertFailsWith<TaskNotFoundException> {
            service.subscribe("user-1", "t-missing", null)
        }

        verify(exactly = 0) { taskEventBusPort.subscribe(any(), any(), any()) }
        verify(exactly = 0) { sseBridge.attach(any(), any(), any(), any()) }
    }

    @Test
    fun `다른 userId 면 TaskAccessDeniedException`() {
        every { taskRepositoryPort.findById("t-1") } returns task(userId = "user-2")

        assertFailsWith<TaskAccessDeniedException> {
            service.subscribe("user-1", "t-1", null)
        }

        verify(exactly = 0) { taskEventBusPort.streamExists(any()) }
        verify(exactly = 0) { taskEventBusPort.subscribe(any(), any(), any()) }
        verify(exactly = 0) { sseBridge.attach(any(), any(), any(), any()) }
    }

    @Test
    fun `terminal state 이고 stream 이 만료되었으면 StreamUnsupportedException`() {
        every { taskRepositoryPort.findById("t-1") } returns task(state = TaskState.COMPLETED)
        every { taskEventBusPort.streamExists("t-1") } returns false

        assertFailsWith<StreamUnsupportedException> {
            service.subscribe("user-1", "t-1", null)
        }

        verify(exactly = 0) { taskEventBusPort.subscribe(any(), any(), any()) }
        verify(exactly = 0) { sseBridge.attach(any(), any(), any(), any()) }
    }

    @Test
    fun `non-terminal task 이면 fromStreamId 0 으로 subscribe 하고 envelopeId=null 로 attach`() {
        every { taskRepositoryPort.findById("t-1") } returns task(state = TaskState.WORKING)
        val subscription = mockk<Subscription>(relaxed = true)
        every { taskEventBusPort.subscribe("t-1", "0", any()) } returns subscription

        val emitter = service.subscribe("user-1", "t-1", null)

        assertNotNull(emitter)
        // non-terminal 인 경우 streamExists 는 호출되지 않아야 한다 (불필요한 I/O 회피)
        verify(exactly = 0) { taskEventBusPort.streamExists(any()) }
        verify(exactly = 1) { taskEventBusPort.subscribe("t-1", "0", any()) }
        verify(exactly = 1) { sseBridge.attach("t-1", null, emitter, subscription) }
    }

    @Test
    fun `lastEventId 가 있으면 그대로 fromStreamId 로 사용한다`() {
        every { taskRepositoryPort.findById("t-1") } returns task(state = TaskState.WORKING)
        val subscription = mockk<Subscription>(relaxed = true)
        every { taskEventBusPort.subscribe("t-1", "100-0", any()) } returns subscription

        val emitter = service.subscribe("user-1", "t-1", "100-0")

        assertNotNull(emitter)
        verify(exactly = 1) { taskEventBusPort.subscribe("t-1", "100-0", any()) }
        verify(exactly = 1) { sseBridge.attach("t-1", null, emitter, subscription) }
    }

    @Test
    fun `terminal task 라도 stream 이 아직 살아 있으면 backfill 구독한다`() {
        every { taskRepositoryPort.findById("t-1") } returns task(state = TaskState.COMPLETED)
        every { taskEventBusPort.streamExists("t-1") } returns true
        val subscription = mockk<Subscription>(relaxed = true)
        every { taskEventBusPort.subscribe("t-1", "0", any()) } returns subscription

        val emitter = service.subscribe("user-1", "t-1", null)

        assertNotNull(emitter)
        verify(exactly = 1) { taskEventBusPort.subscribe("t-1", "0", any()) }
        verify(exactly = 1) { sseBridge.attach("t-1", null, emitter, subscription) }
    }

    @Test
    fun `subscribe listener 가 TaskEvent 를 받으면 SseBridge send 로 전달 (final flag 포함)`() {
        val storedTask = task(state = TaskState.WORKING)
        every { taskRepositoryPort.findById("t-1") } returns storedTask
        val subscription = mockk<Subscription>(relaxed = true)
        val listenerSlot = slot<(String, TaskEvent) -> Unit>()
        every {
            taskEventBusPort.subscribe("t-1", "0", capture(listenerSlot))
        } returns subscription
        justRun { sseBridge.attach(any(), any(), any(), any()) }

        service.subscribe("user-1", "t-1", null)

        val finalEvent = TaskEvent(
            taskId = "t-1",
            contextId = storedTask.contextId,
            state = TaskState.COMPLETED,
            statusMessage = null,
            artifact = null,
            errorCode = null,
            errorMessage = null,
            final = true,
            timestamp = now.plusSeconds(1),
        )
        listenerSlot.captured.invoke("1712665200000-0", finalEvent)

        verify(exactly = 1) {
            sseBridge.send("t-1", "1712665200000-0", any<A2ATaskDto>(), true)
        }
    }
}

package com.bara.api.adapter.`in`.rest.a2a

import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.A2APart
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskEvent
import com.bara.api.domain.model.TaskState
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class A2ATaskMapperTest {

    private val now = Instant.parse("2026-04-11T00:00:00Z")

    private val baseTask = Task(
        id = "t-1",
        agentId = "a-1",
        agentName = "my-agent",
        userId = "u-1",
        contextId = "c-1",
        state = TaskState.COMPLETED,
        inputMessage = A2AMessage("m-1", "user", listOf(A2APart("text", "hi"))),
        statusMessage = A2AMessage("m-2", "agent", listOf(A2APart("text", "done"))),
        requestId = "r-1",
        createdAt = now,
        updatedAt = now,
        completedAt = now,
        expiredAt = now.plusSeconds(7 * 24 * 3600),
    )

    @Test
    fun `Task를 A2ATaskDto로 변환한다`() {
        val dto = A2ATaskMapper.toDto(baseTask)
        assertEquals("t-1", dto.id)
        assertEquals("c-1", dto.contextId)
        assertEquals("completed", dto.status.state)
        assertEquals("done", dto.status.message?.parts?.firstOrNull()?.text)
        assertEquals("task", dto.kind)
    }

    @Test
    fun `TaskEvent와 baseTask로부터 A2ATaskDto를 합성한다`() {
        val event = TaskEvent(
            taskId = "t-1",
            contextId = "c-1",
            state = TaskState.FAILED,
            statusMessage = A2AMessage("m-3", "agent", listOf(A2APart("text", "err"))),
            errorCode = "agent-failure",
            errorMessage = "boom",
            final = true,
            timestamp = now,
        )
        val dto = A2ATaskMapper.fromEvent(baseTask, event)
        assertEquals("failed", dto.status.state)
        assertEquals("err", dto.status.message?.parts?.firstOrNull()?.text)
    }

    @Test
    fun `TaskState enum name을 A2A wire 문자열로 변환한다`() {
        assertEquals("submitted", A2ATaskMapper.stateToWire(TaskState.SUBMITTED))
        assertEquals("completed", A2ATaskMapper.stateToWire(TaskState.COMPLETED))
        assertEquals("canceled", A2ATaskMapper.stateToWire(TaskState.CANCELED))
    }
}

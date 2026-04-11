package com.bara.api.application.service.query

import com.bara.api.application.port.out.TaskRepositoryPort
import com.bara.api.domain.exception.TaskAccessDeniedException
import com.bara.api.domain.exception.TaskNotFoundException
import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.A2APart
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskState
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertEquals

class GetTaskServiceTest {

    private val taskRepositoryPort = mockk<TaskRepositoryPort>()
    private val service = GetTaskService(taskRepositoryPort)

    private fun sampleTask(
        id: String = "task-1",
        userId: String = "user-1",
        state: TaskState = TaskState.COMPLETED,
    ): Task {
        val now = Instant.parse("2026-04-11T00:00:00Z")
        return Task(
            id = id,
            agentId = "agent-001",
            agentName = "my-agent",
            userId = userId,
            contextId = "ctx-1",
            state = state,
            inputMessage = A2AMessage("m-1", "user", listOf(A2APart("text", "hi"))),
            requestId = "r-1",
            createdAt = now,
            updatedAt = now,
            expiredAt = now.plusSeconds(7 * 24 * 3600),
        )
    }

    @Test
    fun `정상 조회 — 소유자 일치 시 A2ATaskDto 반환`() {
        every { taskRepositoryPort.findById("task-1") } returns sampleTask(state = TaskState.COMPLETED)

        val dto = service.getTask(userId = "user-1", taskId = "task-1")

        assertEquals("task-1", dto.id)
        assertEquals("completed", dto.status.state)
        assertEquals("ctx-1", dto.contextId)
    }

    @Test
    fun `Task 미존재 — TaskNotFoundException`() {
        every { taskRepositoryPort.findById("missing") } returns null

        val ex = assertThrows<TaskNotFoundException> {
            service.getTask(userId = "user-1", taskId = "missing")
        }
        assertEquals("Task not found: missing", ex.message)
    }

    @Test
    fun `소유자 불일치 — TaskAccessDeniedException`() {
        every { taskRepositoryPort.findById("task-1") } returns sampleTask(userId = "owner")

        val ex = assertThrows<TaskAccessDeniedException> {
            service.getTask(userId = "attacker", taskId = "task-1")
        }
        assertEquals("Task access denied: task-1", ex.message)
    }
}

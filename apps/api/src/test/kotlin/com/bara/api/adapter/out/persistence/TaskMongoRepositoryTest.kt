package com.bara.api.adapter.out.persistence

import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.A2APart
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskState
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TaskMongoRepositoryTest {

    private val dataRepository = mockk<TaskMongoDataRepository>()
    private val repository = TaskMongoRepository(dataRepository)

    private val now = Instant.parse("2026-04-11T00:00:00Z")

    private val task = Task(
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
    fun `save는 Document로 변환해 저장하고 Domain으로 반환한다`() {
        val docSlot = slot<TaskDocument>()
        every { dataRepository.save(capture(docSlot)) } answers { docSlot.captured }

        val saved = repository.save(task)

        assertEquals("t-1", saved.id)
        assertEquals(TaskState.SUBMITTED, saved.state)
        verify { dataRepository.save(any<TaskDocument>()) }
    }

    @Test
    fun `findById는 Domain으로 복원한다`() {
        every { dataRepository.findById("t-1") } returns
            Optional.of(TaskDocument.fromDomain(task))

        val found = repository.findById("t-1")
        assertNotNull(found)
        assertEquals("t-1", found.id)
    }

    @Test
    fun `findById 미존재 시 null 반환`() {
        every { dataRepository.findById("missing") } returns Optional.empty()
        val result = repository.findById("missing")
        assertEquals(null, result)
    }
}

package com.bara.api.domain

import com.bara.api.domain.model.TaskState
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskStateTest {

    @Test
    fun `SUBMITTED, WORKING은 비터미널`() {
        assertFalse(TaskState.SUBMITTED.isTerminal)
        assertFalse(TaskState.WORKING.isTerminal)
    }

    @Test
    fun `COMPLETED, FAILED, CANCELED, REJECTED는 터미널`() {
        assertTrue(TaskState.COMPLETED.isTerminal)
        assertTrue(TaskState.FAILED.isTerminal)
        assertTrue(TaskState.CANCELED.isTerminal)
        assertTrue(TaskState.REJECTED.isTerminal)
    }
}

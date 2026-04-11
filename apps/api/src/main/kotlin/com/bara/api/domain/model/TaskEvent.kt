package com.bara.api.domain.model

import java.time.Instant

data class TaskEvent(
    val taskId: String,
    val contextId: String,
    val state: TaskState,
    val statusMessage: A2AMessage? = null,
    val artifact: A2AArtifact? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val final: Boolean,
    val timestamp: Instant,
) {
    companion object {
        fun of(task: Task): TaskEvent = TaskEvent(
            taskId = task.id,
            contextId = task.contextId,
            state = task.state,
            statusMessage = task.statusMessage,
            artifact = task.artifacts.firstOrNull(),
            errorCode = task.errorCode,
            errorMessage = task.errorMessage,
            final = task.state.isTerminal,
            timestamp = task.updatedAt,
        )
    }
}

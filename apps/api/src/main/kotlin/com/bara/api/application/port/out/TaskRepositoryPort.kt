package com.bara.api.application.port.out

import com.bara.api.domain.model.A2AArtifact
import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskState
import java.time.Instant

interface TaskRepositoryPort {
    fun save(task: Task): Task

    fun findById(id: String): Task?

    fun updateState(
        id: String,
        state: TaskState,
        statusMessage: A2AMessage?,
        artifacts: List<A2AArtifact>,
        errorCode: String?,
        errorMessage: String?,
        updatedAt: Instant,
        completedAt: Instant?,
        expiredAt: Instant?,
    ): Boolean
}

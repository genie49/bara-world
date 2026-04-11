package com.bara.api.adapter.`in`.rest.a2a

import com.bara.api.domain.model.A2AArtifact
import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskEvent
import com.bara.api.domain.model.TaskState

object A2ATaskMapper {

    fun toDto(task: Task): A2ATaskDto = A2ATaskDto(
        id = task.id,
        contextId = task.contextId,
        status = A2ATaskStatusDto(
            state = stateToWire(task.state),
            message = task.statusMessage?.let(::toMessageDto),
            timestamp = task.updatedAt.toString(),
        ),
        artifacts = task.artifacts.map(::toArtifactDto),
    )

    fun fromEvent(task: Task, event: TaskEvent): A2ATaskDto = A2ATaskDto(
        id = event.taskId,
        contextId = event.contextId,
        status = A2ATaskStatusDto(
            state = stateToWire(event.state),
            message = event.statusMessage?.let(::toMessageDto),
            timestamp = event.timestamp.toString(),
        ),
        artifacts = listOfNotNull(event.artifact?.let(::toArtifactDto)).ifEmpty {
            task.artifacts.map(::toArtifactDto)
        },
    )

    fun stateToWire(state: TaskState): String = when (state) {
        TaskState.SUBMITTED -> "submitted"
        TaskState.WORKING -> "working"
        TaskState.COMPLETED -> "completed"
        TaskState.FAILED -> "failed"
        TaskState.CANCELED -> "canceled"
        TaskState.REJECTED -> "rejected"
    }

    private fun toMessageDto(m: A2AMessage): A2AMessageDto = A2AMessageDto(
        messageId = m.messageId,
        role = m.role,
        parts = m.parts.map { A2APartDto(kind = it.kind, text = it.text) },
    )

    private fun toArtifactDto(a: A2AArtifact): A2AArtifactDto = A2AArtifactDto(
        artifactId = a.artifactId,
        name = a.name,
        parts = a.parts.map { A2APartDto(kind = it.kind, text = it.text) },
    )
}

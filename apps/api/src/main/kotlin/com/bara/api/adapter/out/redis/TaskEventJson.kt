package com.bara.api.adapter.out.redis

import com.bara.api.domain.model.TaskEvent
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * Redis Stream 엔트리 payload 직렬화. 단일 "event" 필드에 전체 JSON 저장.
 */
object TaskEventJson {

    fun serialize(mapper: ObjectMapper, event: TaskEvent): String =
        mapper.writeValueAsString(WireFormat.from(event))

    fun deserialize(mapper: ObjectMapper, json: String): TaskEvent =
        mapper.readValue(json, WireFormat::class.java).toDomain()

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class WireFormat(
        val taskId: String,
        val contextId: String,
        val state: String,
        val statusMessage: com.bara.api.domain.model.A2AMessage? = null,
        val artifact: com.bara.api.domain.model.A2AArtifact? = null,
        val errorCode: String? = null,
        val errorMessage: String? = null,
        val final: Boolean,
        val timestamp: String,
    ) {
        fun toDomain(): TaskEvent = TaskEvent(
            taskId = taskId,
            contextId = contextId,
            state = com.bara.api.domain.model.TaskState.valueOf(state.uppercase().replace('-', '_')),
            statusMessage = statusMessage,
            artifact = artifact,
            errorCode = errorCode,
            errorMessage = errorMessage,
            final = final,
            timestamp = Instant.parse(timestamp),
        )

        companion object {
            fun from(e: TaskEvent): WireFormat = WireFormat(
                taskId = e.taskId,
                contextId = e.contextId,
                state = e.state.name.lowercase(),
                statusMessage = e.statusMessage,
                artifact = e.artifact,
                errorCode = e.errorCode,
                errorMessage = e.errorMessage,
                final = e.final,
                timestamp = e.timestamp.toString(),
            )
        }
    }
}

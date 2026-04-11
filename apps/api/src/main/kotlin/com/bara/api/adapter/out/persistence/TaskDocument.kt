package com.bara.api.adapter.out.persistence

import com.bara.api.domain.model.A2AArtifact
import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.A2APart
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskState
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "tasks")
data class TaskDocument(
    @Id val id: String,
    val agentId: String,
    val agentName: String,
    val userId: String,
    val contextId: String,
    val state: String,
    val inputMessage: A2AMessageDoc,
    val statusMessage: A2AMessageDoc? = null,
    val artifacts: List<A2AArtifactDoc> = emptyList(),
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val requestId: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant? = null,
    @Indexed(expireAfterSeconds = 0)
    val expiredAt: Instant,
) {
    fun toDomain(): Task = Task(
        id = id,
        agentId = agentId,
        agentName = agentName,
        userId = userId,
        contextId = contextId,
        state = TaskState.valueOf(state),
        inputMessage = inputMessage.toDomain(),
        statusMessage = statusMessage?.toDomain(),
        artifacts = artifacts.map { it.toDomain() },
        errorCode = errorCode,
        errorMessage = errorMessage,
        requestId = requestId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
        expiredAt = expiredAt,
    )

    companion object {
        fun fromDomain(t: Task): TaskDocument = TaskDocument(
            id = t.id,
            agentId = t.agentId,
            agentName = t.agentName,
            userId = t.userId,
            contextId = t.contextId,
            state = t.state.name,
            inputMessage = A2AMessageDoc.fromDomain(t.inputMessage),
            statusMessage = t.statusMessage?.let(A2AMessageDoc::fromDomain),
            artifacts = t.artifacts.map(A2AArtifactDoc::fromDomain),
            errorCode = t.errorCode,
            errorMessage = t.errorMessage,
            requestId = t.requestId,
            createdAt = t.createdAt,
            updatedAt = t.updatedAt,
            completedAt = t.completedAt,
            expiredAt = t.expiredAt,
        )
    }
}

data class A2AMessageDoc(
    val messageId: String,
    val role: String,
    val parts: List<A2APartDoc>,
) {
    fun toDomain(): A2AMessage = A2AMessage(
        messageId = messageId,
        role = role,
        parts = parts.map { it.toDomain() },
    )

    companion object {
        fun fromDomain(m: A2AMessage): A2AMessageDoc = A2AMessageDoc(
            messageId = m.messageId,
            role = m.role,
            parts = m.parts.map(A2APartDoc::fromDomain),
        )
    }
}

data class A2APartDoc(val kind: String, val text: String) {
    fun toDomain(): A2APart = A2APart(kind = kind, text = text)

    companion object {
        fun fromDomain(p: A2APart): A2APartDoc = A2APartDoc(kind = p.kind, text = p.text)
    }
}

data class A2AArtifactDoc(
    val artifactId: String,
    val name: String? = null,
    val parts: List<A2APartDoc> = emptyList(),
) {
    fun toDomain(): A2AArtifact = A2AArtifact(
        artifactId = artifactId,
        name = name,
        parts = parts.map { it.toDomain() },
    )

    companion object {
        fun fromDomain(a: A2AArtifact): A2AArtifactDoc = A2AArtifactDoc(
            artifactId = a.artifactId,
            name = a.name,
            parts = a.parts.map(A2APartDoc::fromDomain),
        )
    }
}

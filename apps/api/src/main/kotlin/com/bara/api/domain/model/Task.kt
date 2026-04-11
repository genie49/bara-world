package com.bara.api.domain.model

import java.time.Instant

data class Task(
    val id: String,                    // UUID v4
    val agentId: String,
    val agentName: String,
    val userId: String,
    val contextId: String,
    val state: TaskState,
    val inputMessage: A2AMessage,
    val statusMessage: A2AMessage? = null,
    val artifacts: List<A2AArtifact> = emptyList(),
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val requestId: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant? = null,
    val expiredAt: Instant,
)

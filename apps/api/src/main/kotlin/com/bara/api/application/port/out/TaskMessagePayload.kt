package com.bara.api.application.port.out

import com.bara.api.domain.model.A2AMessage

data class TaskMessagePayload(
    val taskId: String,
    val contextId: String,
    val userId: String,
    val requestId: String,
    val resultTopic: String,            // "results.api"
    val allowedAgents: List<String>,    // 현재는 빈 리스트
    val message: A2AMessage,
)

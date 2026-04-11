package com.bara.api.application.port.`in`.command

import com.bara.api.adapter.`in`.rest.a2a.A2ATaskDto
import java.util.concurrent.CompletableFuture

interface SendMessageUseCase {
    fun sendBlocking(
        userId: String,
        agentName: String,
        request: SendMessageRequest,
    ): CompletableFuture<A2ATaskDto>

    data class SendMessageRequest(
        val messageId: String,
        val text: String,
        val contextId: String?,
    )
}

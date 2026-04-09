package com.bara.api.application.port.`in`.command

interface SendMessageUseCase {
    fun sendMessage(userId: String, agentName: String, request: SendMessageRequest): String

    data class SendMessageRequest(
        val messageId: String,
        val text: String,
        val contextId: String?,
    )
}

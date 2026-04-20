package com.bara.api.application.port.`in`.command

import com.bara.api.adapter.`in`.rest.a2a.A2ATaskDto
import java.util.concurrent.CompletableFuture

interface SendMessageUseCase {
    fun sendBlocking(
        userId: String,
        agentName: String,
        request: SendMessageRequest,
    ): CompletableFuture<A2ATaskDto>

    /**
     * Async submit: Kafka publish ack 까지만 기다린 후 즉시 submitted Task 를 DTO 로 반환한다.
     * 결과는 클라이언트가 GET /agents/{name}/tasks/{taskId} 로 폴링한다.
     * Agent 응답 대기/결과 변환은 하지 않는다.
     */
    fun sendAsync(
        userId: String,
        agentName: String,
        request: SendMessageRequest,
    ): A2ATaskDto

    data class SendMessageRequest(
        val messageId: String,
        val text: String,
        val contextId: String?,
    )
}

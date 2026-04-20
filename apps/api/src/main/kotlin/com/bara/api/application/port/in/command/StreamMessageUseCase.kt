package com.bara.api.application.port.`in`.command

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

interface StreamMessageUseCase {
    /**
     * 새 태스크를 생성하고 SSE 로 진행 상태를 스트리밍한다.
     * 반환된 [SseEmitter] 는 컨트롤러가 그대로 리턴한다 — Spring MVC 가 async response 로 연결을 유지한다.
     */
    fun stream(
        userId: String,
        agentName: String,
        envelopeId: JsonNode?,
        request: SendMessageUseCase.SendMessageRequest,
    ): SseEmitter
}

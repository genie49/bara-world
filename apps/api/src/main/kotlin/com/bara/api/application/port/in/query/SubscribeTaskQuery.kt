package com.bara.api.application.port.`in`.query

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

interface SubscribeTaskQuery {
    /**
     * 기존 태스크에 대한 SSE 재연결.
     * @param lastEventId SSE 표준 Last-Event-ID 헤더. Redis Stream entry id 와 동일 포맷.
     *                    null 이면 "0" (처음부터 backfill).
     */
    fun subscribe(userId: String, taskId: String, lastEventId: String?): SseEmitter
}

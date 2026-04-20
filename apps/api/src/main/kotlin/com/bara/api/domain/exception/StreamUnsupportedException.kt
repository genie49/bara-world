package com.bara.api.domain.exception

/**
 * SSE 재연결(`:subscribe`) 시점에 Redis Stream 이 이미 만료된 경우.
 * 클라이언트는 `GET /tasks/{id}` 폴링으로 전환해야 한다.
 * HTTP 410 GONE + JSON-RPC `-32067` 로 매핑.
 */
class StreamUnsupportedException(
    message: String = "Task stream is no longer available",
) : A2AException(A2AErrorCodes.STREAM_UNSUPPORTED, message)

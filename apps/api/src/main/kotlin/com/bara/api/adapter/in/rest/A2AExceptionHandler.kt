package com.bara.api.adapter.`in`.rest

import com.bara.api.adapter.`in`.rest.a2a.JsonRpcError
import com.bara.api.adapter.`in`.rest.a2a.JsonRpcResponse
import com.bara.api.domain.exception.A2AErrorCodes
import com.bara.api.domain.exception.A2AException
import com.bara.common.logging.WideEvent
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class A2AExceptionHandler {

    @ExceptionHandler(A2AException::class)
    fun handleA2A(ex: A2AException): ResponseEntity<JsonRpcResponse<Nothing>> {
        val httpStatus = httpFor(ex.code)
        val outcome = outcomeFor(ex.code)
        WideEvent.put("error_type", ex.javaClass.simpleName)
        WideEvent.put("error_code", ex.code)
        WideEvent.put("outcome", outcome)
        WideEvent.message("A2A 예외: ${ex.message}")

        return ResponseEntity.status(httpStatus)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                JsonRpcResponse(
                    id = null,
                    result = null,
                    error = JsonRpcError(code = ex.code, message = ex.message ?: "A2A error"),
                ),
            )
    }

    private fun httpFor(code: Int): HttpStatus = when (code) {
        A2AErrorCodes.KAFKA_PUBLISH_FAILED -> HttpStatus.BAD_GATEWAY
        A2AErrorCodes.AGENT_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE
        A2AErrorCodes.AGENT_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT
        A2AErrorCodes.TASK_NOT_FOUND -> HttpStatus.NOT_FOUND
        A2AErrorCodes.TASK_ACCESS_DENIED -> HttpStatus.FORBIDDEN
        A2AErrorCodes.STREAM_UNSUPPORTED -> HttpStatus.GONE
        else -> HttpStatus.INTERNAL_SERVER_ERROR
    }

    private fun outcomeFor(code: Int): String = when (code) {
        A2AErrorCodes.KAFKA_PUBLISH_FAILED -> "kafka_publish_failed"
        A2AErrorCodes.AGENT_UNAVAILABLE -> "agent_unavailable"
        A2AErrorCodes.AGENT_TIMEOUT -> "agent_timeout"
        A2AErrorCodes.TASK_NOT_FOUND -> "task_not_found"
        A2AErrorCodes.TASK_ACCESS_DENIED -> "task_access_denied"
        A2AErrorCodes.STREAM_UNSUPPORTED -> "stream_expired"
        else -> "a2a_error"
    }
}

package com.bara.api.adapter.`in`.rest

import com.bara.api.domain.exception.AgentNameAlreadyExistsException
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.common.logging.WideEvent
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(AgentNotFoundException::class)
    fun handleAgentNotFound(ex: AgentNotFoundException): ResponseEntity<ErrorResponse> {
        WideEvent.put("error_type", "AgentNotFoundException")
        WideEvent.put("outcome", "agent_not_found")
        WideEvent.message("Agent 조회 실패")
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("agent_not_found", ex.message ?: "Agent not found"))
    }

    @ExceptionHandler(AgentNameAlreadyExistsException::class)
    fun handleAgentNameAlreadyExists(ex: AgentNameAlreadyExistsException): ResponseEntity<ErrorResponse> {
        WideEvent.put("error_type", "AgentNameAlreadyExistsException")
        WideEvent.put("outcome", "agent_name_already_exists")
        WideEvent.message("Agent 이름 중복")
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("agent_name_already_exists", ex.message ?: "Agent name already exists"))
    }
}

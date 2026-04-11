package com.bara.api.adapter.`in`.rest

import com.bara.api.domain.exception.AgentNameAlreadyExistsException
import com.bara.api.domain.exception.AgentNotRegisteredException
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.api.domain.exception.AgentOwnershipException
import com.bara.api.domain.exception.AgentTimeoutException
import com.bara.api.domain.exception.AgentUnavailableException
import com.bara.api.domain.exception.KafkaPublishException
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

    @ExceptionHandler(AgentOwnershipException::class)
    fun handleAgentOwnership(ex: AgentOwnershipException): ResponseEntity<ErrorResponse> {
        WideEvent.put("error_type", "AgentOwnershipException")
        WideEvent.put("outcome", "agent_ownership_denied")
        WideEvent.message("Agent 소유권 불일치")
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ErrorResponse("agent_ownership_denied", ex.message ?: "Agent does not belong to this provider"))
    }

    @ExceptionHandler(AgentNotRegisteredException::class)
    fun handleAgentNotRegistered(ex: AgentNotRegisteredException): ResponseEntity<ErrorResponse> {
        WideEvent.put("error_type", "AgentNotRegisteredException")
        WideEvent.put("outcome", "agent_not_registered")
        WideEvent.message("Agent 미등록 상태")
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("agent_not_registered", ex.message ?: "Agent is not registered"))
    }

    @ExceptionHandler(AgentUnavailableException::class)
    fun handleAgentUnavailable(ex: AgentUnavailableException): ResponseEntity<ErrorResponse> {
        WideEvent.put("error_type", "AgentUnavailableException")
        WideEvent.put("outcome", "agent_unavailable")
        WideEvent.message("Agent 비활성")
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ErrorResponse("agent_unavailable", ex.message ?: "Agent is not available"))
    }

    @ExceptionHandler(AgentTimeoutException::class)
    fun handleAgentTimeout(ex: AgentTimeoutException): ResponseEntity<ErrorResponse> {
        WideEvent.put("error_type", "AgentTimeoutException")
        WideEvent.put("outcome", "agent_timeout")
        WideEvent.message("Agent 응답 타임아웃")
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
            .body(ErrorResponse("agent_timeout", ex.message ?: "Agent did not respond within timeout"))
    }

    @ExceptionHandler(KafkaPublishException::class)
    fun handleKafkaPublish(ex: KafkaPublishException): ResponseEntity<ErrorResponse> {
        WideEvent.put("error_type", "KafkaPublishException")
        WideEvent.put("outcome", "kafka_publish_failed")
        WideEvent.message("Kafka publish 실패")
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(ErrorResponse("kafka_publish_failed", ex.message ?: "Kafka publish failed"))
    }
}

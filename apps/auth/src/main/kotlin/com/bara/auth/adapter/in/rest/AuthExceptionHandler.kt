package com.bara.auth.adapter.`in`.rest

import com.bara.auth.config.GoogleOAuthProperties
import com.bara.auth.domain.exception.ApiKeyLimitExceededException
import com.bara.auth.domain.exception.ApiKeyNotFoundException
import com.bara.auth.domain.exception.GoogleExchangeFailedException
import com.bara.auth.domain.exception.InvalidIdTokenException
import com.bara.auth.domain.exception.InvalidOAuthStateException
import com.bara.auth.domain.exception.InvalidTokenException
import com.bara.auth.domain.exception.ProviderAlreadyExistsException
import com.bara.auth.domain.exception.ProviderNotActiveException
import com.bara.auth.domain.exception.ProviderNotFoundException
import com.bara.common.logging.WideEvent
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

data class ErrorResponse(val error: String, val message: String)

@RestControllerAdvice
class AuthExceptionHandler(
    private val googleProps: GoogleOAuthProperties,
) {

    @ExceptionHandler(InvalidOAuthStateException::class)
    fun handleInvalidState(): ResponseEntity<Void> {
        WideEvent.put("error_type", "InvalidOAuthStateException")
        WideEvent.put("outcome", "invalid_state")
        WideEvent.message("OAuth state 검증 실패")
        return redirectWithError("invalid_state")
    }

    @ExceptionHandler(GoogleExchangeFailedException::class)
    fun handleExchangeFailed(): ResponseEntity<Void> {
        WideEvent.put("error_type", "GoogleExchangeFailedException")
        WideEvent.put("outcome", "exchange_failed")
        WideEvent.message("Google code 교환 실패")
        return redirectWithError("google_exchange_failed")
    }

    @ExceptionHandler(InvalidIdTokenException::class)
    fun handleInvalidIdToken(): ResponseEntity<Void> {
        WideEvent.put("error_type", "InvalidIdTokenException")
        WideEvent.put("outcome", "invalid_id_token")
        WideEvent.message("ID token 검증 실패")
        return redirectWithError("invalid_id_token")
    }

    @ExceptionHandler(InvalidTokenException::class)
    fun handleInvalidToken(): ResponseEntity<Void> {
        WideEvent.put("error_type", "InvalidTokenException")
        WideEvent.put("outcome", "invalid_token")
        WideEvent.message("토큰 검증 실패")
        return ResponseEntity(HttpStatus.UNAUTHORIZED)
    }

    @ExceptionHandler(ProviderAlreadyExistsException::class)
    fun handleProviderAlreadyExists(ex: ProviderAlreadyExistsException): ResponseEntity<ErrorResponse> {
        WideEvent.put("error_type", "ProviderAlreadyExistsException")
        WideEvent.put("outcome", "provider_already_exists")
        WideEvent.message("Provider 중복 등록 시도")
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("provider_already_exists", ex.message ?: "Provider already exists"))
    }

    @ExceptionHandler(ProviderNotFoundException::class)
    fun handleProviderNotFound(ex: ProviderNotFoundException): ResponseEntity<ErrorResponse> {
        WideEvent.put("error_type", "ProviderNotFoundException")
        WideEvent.put("outcome", "provider_not_found")
        WideEvent.message("Provider 조회 실패")
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("provider_not_found", ex.message ?: "Provider not found"))
    }

    @ExceptionHandler(ProviderNotActiveException::class)
    fun handleProviderNotActive(ex: ProviderNotActiveException): ResponseEntity<ErrorResponse> {
        WideEvent.put("error_type", "ProviderNotActiveException")
        WideEvent.put("outcome", "provider_not_active")
        WideEvent.message("비활성 Provider 접근 시도")
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ErrorResponse("provider_not_active", ex.message ?: "Provider is not active"))
    }

    @ExceptionHandler(ApiKeyLimitExceededException::class)
    fun handleApiKeyLimitExceeded(ex: ApiKeyLimitExceededException): ResponseEntity<ErrorResponse> {
        WideEvent.put("error_type", "ApiKeyLimitExceededException")
        WideEvent.put("outcome", "api_key_limit_exceeded")
        WideEvent.message("API Key 한도 초과")
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ErrorResponse("api_key_limit_exceeded", ex.message ?: "API key limit exceeded"))
    }

    @ExceptionHandler(ApiKeyNotFoundException::class)
    fun handleApiKeyNotFound(ex: ApiKeyNotFoundException): ResponseEntity<ErrorResponse> {
        WideEvent.put("error_type", "ApiKeyNotFoundException")
        WideEvent.put("outcome", "api_key_not_found")
        WideEvent.message("API Key 조회 실패")
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("api_key_not_found", ex.message ?: "API key not found"))
    }

    private fun redirectWithError(code: String): ResponseEntity<Void> {
        val uri = URI.create("${frontendCallbackBase()}?error=$code")
        val headers = HttpHeaders().apply { location = uri }
        return ResponseEntity(headers, HttpStatus.FOUND)
    }

    private fun frontendCallbackBase(): String {
        return googleProps.redirectUri.replace("/auth/google/callback", "/auth/callback")
    }
}

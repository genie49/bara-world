package com.bara.auth.adapter.`in`.rest

import com.bara.auth.config.GoogleOAuthProperties
import com.bara.auth.domain.exception.GoogleExchangeFailedException
import com.bara.auth.domain.exception.InvalidIdTokenException
import com.bara.auth.domain.exception.InvalidOAuthStateException
import com.bara.common.logging.WideEvent
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

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

    private fun redirectWithError(code: String): ResponseEntity<Void> {
        val uri = URI.create("${frontendCallbackBase()}?error=$code")
        val headers = HttpHeaders().apply { location = uri }
        return ResponseEntity(headers, HttpStatus.FOUND)
    }

    private fun frontendCallbackBase(): String {
        return googleProps.redirectUri.replace("/auth/google/callback", "/auth/callback")
    }
}

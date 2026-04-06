package com.bara.auth.adapter.`in`.rest

import com.bara.auth.config.GoogleOAuthProperties
import com.bara.auth.domain.exception.GoogleExchangeFailedException
import com.bara.auth.domain.exception.InvalidIdTokenException
import com.bara.auth.domain.exception.InvalidOAuthStateException
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
    fun handleInvalidState(): ResponseEntity<Void> = redirectWithError("invalid_state")

    @ExceptionHandler(GoogleExchangeFailedException::class)
    fun handleExchangeFailed(): ResponseEntity<Void> = redirectWithError("google_exchange_failed")

    @ExceptionHandler(InvalidIdTokenException::class)
    fun handleInvalidIdToken(): ResponseEntity<Void> = redirectWithError("invalid_id_token")

    private fun redirectWithError(code: String): ResponseEntity<Void> {
        val uri = URI.create("${frontendCallbackBase()}?error=$code")
        val headers = HttpHeaders().apply { location = uri }
        return ResponseEntity(headers, HttpStatus.FOUND)
    }

    private fun frontendCallbackBase(): String {
        // redirect-uri: http://localhost:5173/auth/google/callback
        // → http://localhost:5173/auth/callback
        return googleProps.redirectUri.replace("/auth/google/callback", "/auth/callback")
    }
}

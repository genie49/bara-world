package com.bara.auth.adapter.`in`.rest

import com.bara.auth.application.port.`in`.query.ValidateTokenUseCase
import com.bara.auth.domain.exception.InvalidTokenException
import com.bara.auth.domain.model.ValidateResult
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class ValidateController(
    private val validateTokenUseCase: ValidateTokenUseCase,
) {
    @GetMapping("/validate")
    fun validate(
        @RequestHeader("Authorization", required = false) authorization: String?,
    ): ResponseEntity<Void> {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }

        val token = authorization.removePrefix("Bearer ").trim()
        val result = try {
            validateTokenUseCase.validate(token)
        } catch (e: InvalidTokenException) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }

        val requestId = UUID.randomUUID().toString()

        return when (result) {
            is ValidateResult.UserResult -> ResponseEntity.ok()
                .header("X-User-Id", result.userId)
                .header("X-User-Role", result.role)
                .header("X-Request-Id", requestId)
                .build()

            is ValidateResult.ProviderResult -> ResponseEntity.ok()
                .header("X-Provider-Id", result.providerId)
                .header("X-Request-Id", requestId)
                .build()
        }
    }
}

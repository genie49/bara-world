package com.bara.auth.adapter.`in`.rest

import com.bara.auth.application.port.`in`.query.ValidateTokenUseCase
import com.bara.auth.domain.exception.InvalidTokenException
import com.bara.auth.domain.model.ValidateResult
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.headers.Header
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Tag(name = "Token Validation", description = "JWT 토큰 검증 (Traefik forwardAuth)")
@RestController
class ValidateController(
    private val validateTokenUseCase: ValidateTokenUseCase,
) {
    @Operation(
        summary = "JWT 토큰 검증",
        description = "Traefik forwardAuth용 내부 엔드포인트. 유효한 토큰이면 200 + 사용자 정보 헤더를 반환하고, 무효하면 401을 반환한다. User JWT인 경우 X-User-Id/X-User-Role 헤더를, API Key인 경우 X-Provider-Id 헤더를 반환한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "토큰 유효 — 사용자 정보 헤더 포함",
                headers = [
                    Header(name = "X-User-Id", description = "사용자 ID (JWT인 경우)", schema = Schema(type = "string", example = "6615e1a2b3c4d5e6f7890abc")),
                    Header(name = "X-User-Role", description = "사용자 역할 (JWT인 경우)", schema = Schema(type = "string", example = "USER")),
                    Header(name = "X-Provider-Id", description = "Provider ID (API Key인 경우)", schema = Schema(type = "string", example = "6615e1a2b3c4d5e6f7890def")),
                    Header(name = "X-Request-Id", description = "요청 추적 ID", schema = Schema(type = "string", example = "550e8400-e29b-41d4-a716-446655440000")),
                ],
            ),
            ApiResponse(
                responseCode = "401",
                description = "토큰이 없거나, 형식이 잘못되었거나, 만료/무효한 토큰",
                content = [io.swagger.v3.oas.annotations.media.Content()],
            ),
        ],
    )
    @GetMapping("/validate")
    fun validate(
        @Parameter(description = "Bearer 토큰 또는 API Key", example = "Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...")
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

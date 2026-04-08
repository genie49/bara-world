package com.bara.auth.adapter.`in`.rest

import com.bara.auth.application.port.`in`.command.RefreshTokenUseCase
import com.bara.common.logging.WideEvent
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@Schema(description = "토큰 갱신 요청")
data class RefreshRequest(
    @field:Schema(description = "갱신에 사용할 Refresh Token", example = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...")
    val refreshToken: String,
)

@Schema(description = "토큰 갱신 응답")
data class RefreshResponse(
    @field:Schema(description = "새로 발급된 Access Token", example = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...")
    val accessToken: String,
    @field:Schema(description = "새로 발급된 Refresh Token", example = "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...")
    val refreshToken: String,
    @field:Schema(description = "Access Token 만료 시간 (초)", example = "3600")
    val expiresIn: Long,
)

@Tag(name = "Token", description = "Access Token 갱신")
@RestController
class RefreshController(
    private val useCase: RefreshTokenUseCase,
) {
    @Operation(
        summary = "Access Token 갱신",
        description = "Refresh Token을 사용하여 새로운 Access Token과 Refresh Token을 발급받는다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "토큰 갱신 성공",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = RefreshResponse::class))],
            ),
            ApiResponse(
                responseCode = "401",
                description = "Refresh Token이 만료되었거나 유효하지 않음",
                content = [Content()],
            ),
        ],
    )
    @PostMapping("/refresh")
    fun refresh(@RequestBody request: RefreshRequest): ResponseEntity<RefreshResponse> {
        val tokenPair = useCase.refresh(request.refreshToken)

        WideEvent.put("outcome", "token_refreshed")
        WideEvent.message("토큰 갱신 완료")

        return ResponseEntity.ok(
            RefreshResponse(
                accessToken = tokenPair.accessToken,
                refreshToken = tokenPair.refreshToken,
                expiresIn = 3600,
            )
        )
    }
}

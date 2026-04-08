package com.bara.auth.adapter.`in`.rest

import com.bara.auth.application.port.`in`.command.RegisterProviderUseCase
import com.bara.auth.application.port.`in`.query.GetProviderQuery
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Provider", description = "Provider 등록/조회")
@RestController
@RequestMapping("/provider")
class ProviderController(
    private val registerUseCase: RegisterProviderUseCase,
    private val getProviderQuery: GetProviderQuery,
) {
    @Operation(
        summary = "내 Provider 정보 조회",
        description = "현재 로그인한 사용자의 Provider 정보를 조회한다. Provider 등록 전이면 404를 반환한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Provider 조회 성공",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ProviderResponse::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "Provider 미등록",
                content = [Content()],
            ),
        ],
    )
    @GetMapping
    fun get(
        @Parameter(description = "Traefik이 주입하는 사용자 ID", example = "6615e1a2b3c4d5e6f7890abc")
        @RequestHeader("X-User-Id") userId: String,
    ): ResponseEntity<ProviderResponse> {
        val provider = getProviderQuery.getByUserId(userId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(
            ProviderResponse(
                id = provider.id,
                name = provider.name,
                status = provider.status.name,
                createdAt = provider.createdAt.toString(),
            )
        )
    }

    @Operation(
        summary = "Provider 등록",
        description = "현재 로그인한 사용자를 Provider로 등록한다. 이미 등록된 경우 409를 반환한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "Provider 등록 성공",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ProviderResponse::class))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "이미 등록된 Provider",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    @PostMapping("/register")
    fun register(
        @Parameter(description = "Traefik이 주입하는 사용자 ID", example = "6615e1a2b3c4d5e6f7890abc")
        @RequestHeader("X-User-Id") userId: String,
        @RequestBody request: RegisterProviderRequest,
    ): ResponseEntity<ProviderResponse> {
        val provider = registerUseCase.register(userId, request.name)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            ProviderResponse(
                id = provider.id,
                name = provider.name,
                status = provider.status.name,
                createdAt = provider.createdAt.toString(),
            )
        )
    }
}

@Schema(description = "Provider 등록 요청")
data class RegisterProviderRequest(
    @field:Schema(description = "Provider 이름", example = "my-ai-company")
    val name: String,
)

@Schema(description = "Provider 정보")
data class ProviderResponse(
    @field:Schema(description = "Provider ID", example = "6615e1a2b3c4d5e6f7890abc")
    val id: String,
    @field:Schema(description = "Provider 이름", example = "my-ai-company")
    val name: String,
    @field:Schema(description = "Provider 상태", example = "ACTIVE")
    val status: String,
    @field:Schema(description = "생성 일시", example = "2026-04-08T12:00:00Z")
    val createdAt: String,
)

package com.bara.auth.adapter.`in`.rest

import com.bara.auth.application.port.`in`.command.DeleteApiKeyUseCase
import com.bara.auth.application.port.`in`.command.IssueApiKeyUseCase
import com.bara.auth.application.port.`in`.command.UpdateApiKeyNameUseCase
import com.bara.auth.application.port.`in`.query.ListApiKeysQuery
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
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "API Key", description = "Provider API Key 관리")
@RestController
@RequestMapping("/provider/api-key")
class ApiKeyController(
    private val issueUseCase: IssueApiKeyUseCase,
    private val listQuery: ListApiKeysQuery,
    private val updateUseCase: UpdateApiKeyNameUseCase,
    private val deleteUseCase: DeleteApiKeyUseCase,
) {
    @Operation(
        summary = "API Key 발급",
        description = "새로운 API Key를 발급한다. 발급 시 원본 키가 한 번만 반환되므로 반드시 저장해야 한다. 최대 5개까지 발급 가능하며, 초과 시 409를 반환한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "API Key 발급 성공",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = IssuedApiKeyResponse::class))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "API Key 한도 초과",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    @PostMapping
    fun issue(
        @Parameter(description = "Traefik이 주입하는 사용자 ID", example = "6615e1a2b3c4d5e6f7890abc")
        @RequestHeader("X-User-Id") userId: String,
        @RequestBody request: IssueApiKeyRequest,
    ): ResponseEntity<IssuedApiKeyResponse> {
        val result = issueUseCase.issue(userId, request.name)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            IssuedApiKeyResponse(
                id = result.apiKey.id,
                name = result.apiKey.name,
                apiKey = result.rawKey,
                prefix = result.apiKey.keyPrefix,
                createdAt = result.apiKey.createdAt.toString(),
            )
        )
    }

    @Operation(
        summary = "API Key 목록 조회",
        description = "현재 사용자가 발급한 모든 API Key 목록을 조회한다. 원본 키는 반환되지 않으며 prefix만 표시된다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiKeyListResponse::class))],
            ),
        ],
    )
    @GetMapping
    fun list(
        @Parameter(description = "Traefik이 주입하는 사용자 ID", example = "6615e1a2b3c4d5e6f7890abc")
        @RequestHeader("X-User-Id") userId: String,
    ): ResponseEntity<ApiKeyListResponse> {
        val keys = listQuery.listByUserId(userId)
        return ResponseEntity.ok(ApiKeyListResponse(keys = keys.map {
            ApiKeyResponse(id = it.id, name = it.name, prefix = it.keyPrefix, createdAt = it.createdAt.toString())
        }))
    }

    @Operation(
        summary = "API Key 이름 변경",
        description = "지정된 API Key의 이름을 변경한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "이름 변경 성공",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiKeyResponse::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "API Key를 찾을 수 없음",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    @PatchMapping("/{keyId}")
    fun updateName(
        @Parameter(description = "Traefik이 주입하는 사용자 ID", example = "6615e1a2b3c4d5e6f7890abc")
        @RequestHeader("X-User-Id") userId: String,
        @Parameter(description = "변경할 API Key의 ID", example = "6615e1a2b3c4d5e6f7890def")
        @PathVariable keyId: String,
        @RequestBody request: UpdateApiKeyNameRequest,
    ): ResponseEntity<ApiKeyResponse> {
        val updated = updateUseCase.update(userId, keyId, request.name)
        return ResponseEntity.ok(
            ApiKeyResponse(
                id = updated.id,
                name = updated.name,
                prefix = updated.keyPrefix,
                createdAt = updated.createdAt.toString(),
            )
        )
    }

    @Operation(
        summary = "API Key 삭제",
        description = "지정된 API Key를 삭제한다. 삭제 후에는 해당 키로 인증할 수 없다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "삭제 성공"),
            ApiResponse(
                responseCode = "404",
                description = "API Key를 찾을 수 없음",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    @DeleteMapping("/{keyId}")
    fun delete(
        @Parameter(description = "Traefik이 주입하는 사용자 ID", example = "6615e1a2b3c4d5e6f7890abc")
        @RequestHeader("X-User-Id") userId: String,
        @Parameter(description = "삭제할 API Key의 ID", example = "6615e1a2b3c4d5e6f7890def")
        @PathVariable keyId: String,
    ): ResponseEntity<Void> {
        deleteUseCase.delete(userId, keyId)
        return ResponseEntity.noContent().build()
    }
}

@Schema(description = "API Key 발급 요청")
data class IssueApiKeyRequest(
    @field:Schema(description = "API Key 이름", example = "production-key")
    val name: String,
)

@Schema(description = "API Key 이름 변경 요청")
data class UpdateApiKeyNameRequest(
    @field:Schema(description = "새로운 API Key 이름", example = "renamed-key")
    val name: String,
)

@Schema(description = "API Key 정보")
data class ApiKeyResponse(
    @field:Schema(description = "API Key ID", example = "6615e1a2b3c4d5e6f7890def")
    val id: String,
    @field:Schema(description = "API Key 이름", example = "production-key")
    val name: String,
    @field:Schema(description = "API Key 앞부분 (마스킹용)", example = "bara_k1_a1b2")
    val prefix: String,
    @field:Schema(description = "생성 일시", example = "2026-04-08T12:00:00Z")
    val createdAt: String,
)

@Schema(description = "API Key 발급 응답 (원본 키 포함)")
data class IssuedApiKeyResponse(
    @field:Schema(description = "API Key ID", example = "6615e1a2b3c4d5e6f7890def")
    val id: String,
    @field:Schema(description = "API Key 이름", example = "production-key")
    val name: String,
    @field:Schema(description = "원본 API Key (발급 시에만 반환)", example = "bara_k1_a1b2c3d4e5f6...")
    val apiKey: String,
    @field:Schema(description = "API Key 앞부분 (마스킹용)", example = "bara_k1_a1b2")
    val prefix: String,
    @field:Schema(description = "생성 일시", example = "2026-04-08T12:00:00Z")
    val createdAt: String,
)

@Schema(description = "API Key 목록 응답")
data class ApiKeyListResponse(
    @field:Schema(description = "API Key 목록")
    val keys: List<ApiKeyResponse>,
)

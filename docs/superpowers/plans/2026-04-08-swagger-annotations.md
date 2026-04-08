# Swagger OpenAPI 어노테이션 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Auth Service(5개 컨트롤러)와 API Service(1개 컨트롤러)의 모든 엔드포인트 및 DTO에 OpenAPI 어노테이션을 추가하여 Swagger UI에서 상세한 API 문서를 제공한다.

**Architecture:** 각 컨트롤러에 `@Tag`, `@Operation`, `@ApiResponse` 어노테이션을 직접 추가하고, 모든 DTO 필드에 `@Schema(description, example)` 어노테이션을 추가한다. 인프라(springdoc 의존성, AutoConfiguration, Traefik 라우팅)는 이미 구축되어 있으므로 순수 어노테이션 작업만 수행한다.

**Tech Stack:** springdoc-openapi 2.8.6, Swagger/OpenAPI 3.0 annotations (`io.swagger.v3.oas.annotations`)

**Spec:** `docs/superpowers/specs/2026-04-08-swagger-annotations-design.md`

**Testing strategy:** 어노테이션 추가는 선언적 작업이므로 TDD 대신 각 태스크 후 빌드 검증(`./gradlew :apps:<service>:compileKotlin`)으로 regression을 확인한다. 최종 태스크에서 전체 테스트를 실행한다.

**커밋 메시지에 Co-Authored-By 트레일러를 붙이지 마라. git commit 시 --no-verify 플래그를 사용하지 마라.**

---

### Task 1: Auth Service — AuthController + RefreshController 어노테이션

**Files:**
- Modify: `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthController.kt`
- Modify: `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/RefreshController.kt`

- [ ] **Step 1: AuthController에 OpenAPI 어노테이션 추가**

`apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthController.kt` 전체를 다음으로 교체:

```kotlin
package com.bara.auth.adapter.`in`.rest

import com.bara.auth.application.port.`in`.command.LoginWithGoogleUseCase
import com.bara.auth.config.GoogleOAuthProperties
import com.bara.common.logging.WideEvent
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@Tag(name = "Google OAuth", description = "Google 소셜 로그인 (브라우저 리다이렉트)")
@RestController
@RequestMapping("/google")
class AuthController(
    private val useCase: LoginWithGoogleUseCase,
    private val googleProps: GoogleOAuthProperties,
) {

    @Operation(
        summary = "Google 로그인 페이지로 리다이렉트",
        description = "브라우저 리다이렉트 방식. Swagger UI에서 직접 테스트 불가. 브라우저에서 직접 접속하면 Google 로그인 페이지로 이동한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "302", description = "Google 로그인 페이지로 리다이렉트 (Location 헤더)"),
        ],
    )
    @GetMapping("/login")
    fun login(): ResponseEntity<Void> {
        val url = useCase.buildLoginUrl()
        WideEvent.put("outcome", "redirect_to_google")
        WideEvent.message("Google 로그인 URL 리다이렉트")
        return redirect(url)
    }

    @Operation(
        summary = "Google OAuth 콜백 처리",
        description = "Google이 호출하는 콜백 엔드포인트. 직접 호출할 필요 없음. 인증 성공 시 프론트엔드로 토큰과 함께 리다이렉트한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "302", description = "프론트엔드로 토큰과 함께 리다이렉트"),
        ],
    )
    @GetMapping("/callback")
    fun callback(
        @Parameter(description = "Google에서 전달한 인증 코드", example = "4/0AX4XfWh...")
        @RequestParam code: String,
        @Parameter(description = "CSRF 방지용 state 값", example = "random-state-string")
        @RequestParam state: String,
    ): ResponseEntity<Void> {
        val tokenPair = useCase.login(code = code, state = state)
        WideEvent.put("outcome", "success")
        WideEvent.message("Google OAuth 콜백 성공")
        return redirect("${frontendCallbackBase()}?token=${tokenPair.accessToken}&refreshToken=${tokenPair.refreshToken}")
    }

    private fun redirect(url: String): ResponseEntity<Void> {
        val headers = HttpHeaders().apply { location = URI.create(url) }
        return ResponseEntity(headers, HttpStatus.FOUND)
    }

    private fun frontendCallbackBase(): String =
        googleProps.redirectUri.replace("/api/auth/google/callback", "/auth/callback")
}
```

- [ ] **Step 2: RefreshController에 OpenAPI 어노테이션 추가**

`apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/RefreshController.kt` 전체를 다음으로 교체:

```kotlin
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
```

- [ ] **Step 3: 빌드 검증**

Run: `./gradlew :apps:auth:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthController.kt apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/RefreshController.kt
git commit -m "docs(auth): add OpenAPI annotations to AuthController and RefreshController"
```

---

### Task 2: Auth Service — ValidateController 어노테이션

**Files:**
- Modify: `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ValidateController.kt`

- [ ] **Step 1: ValidateController에 OpenAPI 어노테이션 추가**

`apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ValidateController.kt` 전체를 다음으로 교체:

```kotlin
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
```

- [ ] **Step 2: 빌드 검증**

Run: `./gradlew :apps:auth:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ValidateController.kt
git commit -m "docs(auth): add OpenAPI annotations to ValidateController"
```

---

### Task 3: Auth Service — ProviderController 어노테이션

**Files:**
- Modify: `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ProviderController.kt`

- [ ] **Step 1: ProviderController에 OpenAPI 어노테이션 추가**

`apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ProviderController.kt` 전체를 다음으로 교체:

```kotlin
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
```

- [ ] **Step 2: 빌드 검증**

Run: `./gradlew :apps:auth:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ProviderController.kt
git commit -m "docs(auth): add OpenAPI annotations to ProviderController"
```

---

### Task 4: Auth Service — ApiKeyController 어노테이션

**Files:**
- Modify: `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ApiKeyController.kt`

- [ ] **Step 1: ApiKeyController에 OpenAPI 어노테이션 추가**

`apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ApiKeyController.kt` 전체를 다음으로 교체:

```kotlin
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
```

- [ ] **Step 2: 빌드 검증**

Run: `./gradlew :apps:auth:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ApiKeyController.kt
git commit -m "docs(auth): add OpenAPI annotations to ApiKeyController"
```

---

### Task 5: Auth Service — ErrorResponse 어노테이션 + Auth 전체 테스트

**Files:**
- Modify: `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthExceptionHandler.kt`

- [ ] **Step 1: ErrorResponse에 @Schema 어노테이션 추가**

`apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthExceptionHandler.kt`에서 `ErrorResponse` data class(21행)를 다음으로 교체:

기존:
```kotlin
data class ErrorResponse(val error: String, val message: String)
```

변경:
```kotlin
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "에러 응답")
data class ErrorResponse(
    @field:Schema(description = "에러 코드", example = "invalid_token")
    val error: String,
    @field:Schema(description = "에러 메시지", example = "토큰이 만료되었거나 유효하지 않습니다")
    val message: String,
)
```

주의: import 문에 `io.swagger.v3.oas.annotations.media.Schema`를 추가해야 한다.

- [ ] **Step 2: Auth 전체 빌드 + 테스트**

Run: `./gradlew :apps:auth:test`
Expected: BUILD SUCCESSFUL, 모든 테스트 통과

- [ ] **Step 3: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthExceptionHandler.kt
git commit -m "docs(auth): add OpenAPI annotations to ErrorResponse"
```

---

### Task 6: API Service — AgentController 어노테이션

**Files:**
- Modify: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentController.kt`

- [ ] **Step 1: AgentController에 OpenAPI 어노테이션 추가**

`apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentController.kt` 전체를 다음으로 교체:

```kotlin
package com.bara.api.adapter.`in`.rest

import com.bara.api.application.port.`in`.command.DeleteAgentUseCase
import com.bara.api.application.port.`in`.command.RegisterAgentUseCase
import com.bara.api.application.port.`in`.query.GetAgentCardQuery
import com.bara.api.application.port.`in`.query.GetAgentQuery
import com.bara.api.application.port.`in`.query.ListAgentsQuery
import com.bara.api.domain.model.AgentCard
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.security.SecurityRequirements
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@Tag(name = "Agent", description = "Agent 등록/조회/삭제")
@RestController
@RequestMapping("/agents")
class AgentController(
    private val registerAgentUseCase: RegisterAgentUseCase,
    private val deleteAgentUseCase: DeleteAgentUseCase,
    private val listAgentsQuery: ListAgentsQuery,
    private val getAgentQuery: GetAgentQuery,
    private val getAgentCardQuery: GetAgentCardQuery,
) {

    @Operation(
        summary = "Agent 등록",
        description = "새로운 Agent를 등록한다. Agent 이름은 Provider 내에서 고유해야 하며, 중복 시 409를 반환한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "201",
                description = "Agent 등록 성공",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = AgentDetailResponse::class))],
            ),
            ApiResponse(
                responseCode = "409",
                description = "Agent 이름 중복",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    @PostMapping
    fun register(
        @Parameter(description = "Traefik이 주입하는 Provider ID", example = "6615e1a2b3c4d5e6f7890abc")
        @RequestHeader("X-Provider-Id") providerId: String,
        @RequestBody request: RegisterAgentRequest,
    ): ResponseEntity<AgentDetailResponse> {
        val agent = registerAgentUseCase.register(providerId, request.toCommand())
        return ResponseEntity.status(HttpStatus.CREATED).body(AgentDetailResponse.from(agent))
    }

    @Operation(
        summary = "Agent 목록 조회",
        description = "등록된 모든 Agent 목록을 조회한다. 인증 불필요.",
    )
    @SecurityRequirements(value = [])
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = AgentListResponse::class))],
            ),
        ],
    )
    @GetMapping
    fun list(): ResponseEntity<AgentListResponse> {
        val agents = listAgentsQuery.listAll()
        return ResponseEntity.ok(AgentListResponse(agents.map { AgentResponse.from(it) }))
    }

    @Operation(
        summary = "Agent 상세 조회",
        description = "Agent ID로 상세 정보를 조회한다. 인증 불필요.",
    )
    @SecurityRequirements(value = [])
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "조회 성공",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = AgentDetailResponse::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "Agent를 찾을 수 없음",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    @GetMapping("/{id}")
    fun getById(
        @Parameter(description = "Agent ID", example = "6615f1a2b3c4d5e6f7890abc")
        @PathVariable id: String,
    ): ResponseEntity<AgentDetailResponse> {
        val agent = getAgentQuery.getById(id)
        return ResponseEntity.ok(AgentDetailResponse.from(agent))
    }

    @Operation(
        summary = "Agent Card 조회 (A2A 프로토콜)",
        description = "A2A 프로토콜 표준 경로로 Agent Card JSON을 반환한다. 인증 불필요.",
    )
    @SecurityRequirements(value = [])
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "Agent Card 조회 성공",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = AgentCard::class))],
            ),
            ApiResponse(
                responseCode = "404",
                description = "Agent를 찾을 수 없음",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    @GetMapping("/{id}/.well-known/agent.json")
    fun getAgentCard(
        @Parameter(description = "Agent ID", example = "6615f1a2b3c4d5e6f7890abc")
        @PathVariable id: String,
    ): ResponseEntity<Any> {
        val card = getAgentCardQuery.getCardById(id)
        return ResponseEntity.ok(card)
    }

    @Operation(
        summary = "Agent 삭제",
        description = "Agent를 삭제한다. 본인이 등록한 Agent만 삭제 가능하며, 다른 Provider의 Agent 삭제 시도 시 404를 반환한다.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "삭제 성공"),
            ApiResponse(
                responseCode = "404",
                description = "Agent를 찾을 수 없거나 권한 없음",
                content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ErrorResponse::class))],
            ),
        ],
    )
    @DeleteMapping("/{id}")
    fun delete(
        @Parameter(description = "Traefik이 주입하는 Provider ID", example = "6615e1a2b3c4d5e6f7890abc")
        @RequestHeader("X-Provider-Id") providerId: String,
        @Parameter(description = "삭제할 Agent ID", example = "6615f1a2b3c4d5e6f7890abc")
        @PathVariable id: String,
    ): ResponseEntity<Void> {
        deleteAgentUseCase.delete(providerId, id)
        return ResponseEntity.noContent().build()
    }
}
```

- [ ] **Step 2: 빌드 검증**

Run: `./gradlew :apps:api:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentController.kt
git commit -m "docs(api): add OpenAPI annotations to AgentController"
```

---

### Task 7: API Service — AgentDtos + AgentCard 어노테이션

**Files:**
- Modify: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentDtos.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/domain/model/AgentCard.kt`

- [ ] **Step 1: AgentDtos.kt에 @Schema 어노테이션 추가**

`apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentDtos.kt` 전체를 다음으로 교체:

```kotlin
package com.bara.api.adapter.`in`.rest

import com.bara.api.application.port.`in`.command.RegisterAgentUseCase
import com.bara.api.domain.model.Agent
import com.bara.api.domain.model.AgentCard
import io.swagger.v3.oas.annotations.media.Schema

// ── Request ──

@Schema(description = "Agent 등록 요청")
data class RegisterAgentRequest(
    @field:Schema(description = "Agent 이름 (Provider 내 고유)", example = "my-translation-agent")
    val name: String,
    @field:Schema(description = "Agent Card 정보")
    val agentCard: AgentCardRequest,
) {
    fun toCommand() = RegisterAgentUseCase.Command(
        name = name,
        agentCard = AgentCard(
            name = agentCard.name,
            description = agentCard.description,
            version = agentCard.version,
            defaultInputModes = agentCard.defaultInputModes,
            defaultOutputModes = agentCard.defaultOutputModes,
            capabilities = AgentCard.AgentCapabilities(
                streaming = agentCard.capabilities.streaming,
                pushNotifications = agentCard.capabilities.pushNotifications,
            ),
            skills = agentCard.skills.map {
                AgentCard.AgentSkill(
                    id = it.id,
                    name = it.name,
                    description = it.description,
                    tags = it.tags,
                    examples = it.examples,
                )
            },
            iconUrl = agentCard.iconUrl,
        ),
    )
}

@Schema(description = "Agent Card 요청")
data class AgentCardRequest(
    @field:Schema(description = "Agent 표시 이름", example = "Translation Agent")
    val name: String,
    @field:Schema(description = "Agent 설명", example = "다국어 번역을 수행하는 AI 에이전트")
    val description: String,
    @field:Schema(description = "버전", example = "1.0.0")
    val version: String,
    @field:Schema(description = "기본 입력 모드", example = "[\"text\"]")
    val defaultInputModes: List<String>,
    @field:Schema(description = "기본 출력 모드", example = "[\"text\"]")
    val defaultOutputModes: List<String>,
    @field:Schema(description = "Agent 기능")
    val capabilities: AgentCapabilitiesRequest = AgentCapabilitiesRequest(),
    @field:Schema(description = "Agent 스킬 목록")
    val skills: List<AgentSkillRequest>,
    @field:Schema(description = "Agent 아이콘 URL", example = "https://example.com/icon.png", nullable = true)
    val iconUrl: String? = null,
)

@Schema(description = "Agent 기능 요청")
data class AgentCapabilitiesRequest(
    @field:Schema(description = "스트리밍 지원 여부", example = "false")
    val streaming: Boolean = false,
    @field:Schema(description = "푸시 알림 지원 여부", example = "false")
    val pushNotifications: Boolean = false,
)

@Schema(description = "Agent 스킬 요청")
data class AgentSkillRequest(
    @field:Schema(description = "스킬 ID", example = "translate")
    val id: String,
    @field:Schema(description = "스킬 이름", example = "번역")
    val name: String,
    @field:Schema(description = "스킬 설명", example = "입력 텍스트를 지정된 언어로 번역합니다")
    val description: String,
    @field:Schema(description = "스킬 태그", example = "[\"translation\", \"multilingual\"]")
    val tags: List<String> = emptyList(),
    @field:Schema(description = "사용 예시", example = "[\"영어를 한국어로 번역해줘\"]")
    val examples: List<String> = emptyList(),
)

// ── Response ──

@Schema(description = "Agent 요약 정보")
data class AgentResponse(
    @field:Schema(description = "Agent ID", example = "6615f1a2b3c4d5e6f7890abc")
    val id: String,
    @field:Schema(description = "Agent 이름", example = "my-translation-agent")
    val name: String,
    @field:Schema(description = "Provider ID", example = "6615e1a2b3c4d5e6f7890abc")
    val providerId: String,
    @field:Schema(description = "생성 일시", example = "2026-04-08T12:00:00Z")
    val createdAt: String,
) {
    companion object {
        fun from(agent: Agent) = AgentResponse(
            id = agent.id,
            name = agent.name,
            providerId = agent.providerId,
            createdAt = agent.createdAt.toString(),
        )
    }
}

@Schema(description = "Agent 상세 정보")
data class AgentDetailResponse(
    @field:Schema(description = "Agent ID", example = "6615f1a2b3c4d5e6f7890abc")
    val id: String,
    @field:Schema(description = "Agent 이름", example = "my-translation-agent")
    val name: String,
    @field:Schema(description = "Provider ID", example = "6615e1a2b3c4d5e6f7890abc")
    val providerId: String,
    @field:Schema(description = "Agent Card 정보")
    val agentCard: AgentCard,
    @field:Schema(description = "생성 일시", example = "2026-04-08T12:00:00Z")
    val createdAt: String,
) {
    companion object {
        fun from(agent: Agent) = AgentDetailResponse(
            id = agent.id,
            name = agent.name,
            providerId = agent.providerId,
            agentCard = agent.agentCard,
            createdAt = agent.createdAt.toString(),
        )
    }
}

@Schema(description = "Agent 목록 응답")
data class AgentListResponse(
    @field:Schema(description = "Agent 목록")
    val agents: List<AgentResponse>,
)

@Schema(description = "에러 응답")
data class ErrorResponse(
    @field:Schema(description = "에러 코드", example = "agent_not_found")
    val error: String,
    @field:Schema(description = "에러 메시지", example = "Agent를 찾을 수 없습니다")
    val message: String,
)
```

- [ ] **Step 2: AgentCard.kt에 @Schema 어노테이션 추가**

`apps/api/src/main/kotlin/com/bara/api/domain/model/AgentCard.kt` 전체를 다음으로 교체:

```kotlin
package com.bara.api.domain.model

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "A2A 프로토콜 Agent Card")
data class AgentCard(
    @field:Schema(description = "Agent 표시 이름", example = "Translation Agent")
    val name: String,
    @field:Schema(description = "Agent 설명", example = "다국어 번역을 수행하는 AI 에이전트")
    val description: String,
    @field:Schema(description = "버전", example = "1.0.0")
    val version: String,
    @field:Schema(description = "기본 입력 모드", example = "[\"text\"]")
    val defaultInputModes: List<String>,
    @field:Schema(description = "기본 출력 모드", example = "[\"text\"]")
    val defaultOutputModes: List<String>,
    @field:Schema(description = "Agent 기능")
    val capabilities: AgentCapabilities,
    @field:Schema(description = "Agent 스킬 목록")
    val skills: List<AgentSkill>,
    @field:Schema(description = "Agent 아이콘 URL", example = "https://example.com/icon.png", nullable = true)
    val iconUrl: String? = null,
) {
    @Schema(description = "Agent 기능")
    data class AgentCapabilities(
        @field:Schema(description = "스트리밍 지원 여부", example = "false")
        val streaming: Boolean = false,
        @field:Schema(description = "푸시 알림 지원 여부", example = "false")
        val pushNotifications: Boolean = false,
    )

    @Schema(description = "Agent 스킬")
    data class AgentSkill(
        @field:Schema(description = "스킬 ID", example = "translate")
        val id: String,
        @field:Schema(description = "스킬 이름", example = "번역")
        val name: String,
        @field:Schema(description = "스킬 설명", example = "입력 텍스트를 지정된 언어로 번역합니다")
        val description: String,
        @field:Schema(description = "스킬 태그", example = "[\"translation\", \"multilingual\"]")
        val tags: List<String> = emptyList(),
        @field:Schema(description = "사용 예시", example = "[\"영어를 한국어로 번역해줘\"]")
        val examples: List<String> = emptyList(),
    )
}
```

- [ ] **Step 3: 빌드 검증**

Run: `./gradlew :apps:api:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentDtos.kt apps/api/src/main/kotlin/com/bara/api/domain/model/AgentCard.kt
git commit -m "docs(api): add OpenAPI annotations to AgentDtos and AgentCard"
```

---

### Task 8: 전체 테스트 검증

**Files:** (수정 없음 — 검증만)

- [ ] **Step 1: Auth 전체 테스트**

Run: `./gradlew :apps:auth:test`
Expected: BUILD SUCCESSFUL, 모든 테스트 통과

- [ ] **Step 2: API 전체 테스트**

Run: `./gradlew :apps:api:test`
Expected: BUILD SUCCESSFUL, 모든 테스트 통과

- [ ] **Step 3: API E2E 테스트**

Run: `./gradlew :apps:api:e2eTest`
Expected: BUILD SUCCESSFUL, 모든 E2E 테스트 통과 (어노테이션 추가가 기존 동작에 영향 없음 확인)

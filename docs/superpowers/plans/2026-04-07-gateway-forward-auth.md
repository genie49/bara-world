# 게이트웨이 인증 + 경로 변경 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Traefik forwardAuth로 게이트웨이 인증을 처리하고, Auth 서비스 경로를 `/auth` → `/api/auth`로 변경하며, 컨트롤러에서 JwtVerifier 직접 호출을 X-User-Id 헤더로 대체한다.

**Architecture:** GET /validate 엔드포인트가 토큰을 검증하고 인증 결과를 응답 헤더로 반환. Traefik forwardAuth 미들웨어가 이를 호출하여 헤더를 업스트림에 주입. context-path 설정으로 경로 일괄 변경.

**Tech Stack:** Kotlin + Spring Boot, Traefik Gateway API + CRD Middleware, React + Vite

---

## File Structure

### Backend 신규

| 파일                                                 | 역할                                       |
| ---------------------------------------------------- | ------------------------------------------ |
| `application/port/in/query/ValidateTokenUseCase.kt`  | 토큰 검증 포트                             |
| `domain/model/ValidateResult.kt`                     | sealed class — UserResult / ProviderResult |
| `application/service/query/ValidateTokenService.kt`  | JWT + API Key 검증 로직                    |
| `adapter/in/rest/ValidateController.kt`              | GET /validate 핸들러                       |
| `test/.../service/query/ValidateTokenServiceTest.kt` | 서비스 테스트                              |
| `test/.../adapter/in/rest/ValidateControllerTest.kt` | 컨트롤러 테스트                            |

### Backend 수정

| 파일                        | 변경                                           |
| --------------------------- | ---------------------------------------------- |
| `application.yml`           | context-path 추가                              |
| `AuthController.kt`         | @RequestMapping 변경 + 리다이렉트 경로 수정    |
| `RefreshController.kt`      | @RequestMapping 변경                           |
| `ProviderController.kt`     | @RequestMapping 변경 + JwtVerifier → X-User-Id |
| `ApiKeyController.kt`       | @RequestMapping 변경 + JwtVerifier → X-User-Id |
| `AuthExceptionHandler.kt`   | 리다이렉트 경로 수정                           |
| `ProviderControllerTest.kt` | JwtVerifier mock 제거 → X-User-Id 헤더         |
| `ApiKeyControllerTest.kt`   | JwtVerifier mock 제거 → X-User-Id 헤더         |
| 기타 테스트                 | context-path 반영                              |

### FE 수정

| 파일                     | 변경                                            |
| ------------------------ | ----------------------------------------------- |
| `pages/LoginPage.tsx`    | `/auth/google/login` → `/api/auth/google/login` |
| `pages/ProviderPage.tsx` | `/auth/provider*` → `/api/auth/provider*`       |
| `lib/api.ts`             | `/auth/refresh` → `/api/auth/refresh`           |
| `vite.config.ts`         | proxy `/auth` → `/api/auth`                     |

### K8s 신규/수정

| 파일                                         | 역할                                 |
| -------------------------------------------- | ------------------------------------ |
| `infra/k8s/base/gateway/middlewares.yaml`    | 신규 — forwardAuth + CORS Middleware |
| `infra/k8s/base/gateway/routes.yaml`         | 수정 — 경로 재구성 + Middleware 참조 |
| `infra/k8s/base/gateway/traefik-config.yaml` | 수정 — CRD 활성화                    |
| `infra/k8s/base/core/auth.yaml`              | 수정 — health probe 경로             |

---

## Task 1: ValidateResult 도메인 모델 + ValidateTokenUseCase 포트

**Files:**

- Create: `apps/auth/src/main/kotlin/com/bara/auth/domain/model/ValidateResult.kt`
- Create: `apps/auth/src/main/kotlin/com/bara/auth/application/port/in/query/ValidateTokenUseCase.kt`

- [ ] **Step 1: ValidateResult sealed class 작성**

```kotlin
// apps/auth/src/main/kotlin/com/bara/auth/domain/model/ValidateResult.kt
package com.bara.auth.domain.model

sealed class ValidateResult {
    data class UserResult(val userId: String, val role: String) : ValidateResult()
    data class ProviderResult(val providerId: String) : ValidateResult()
}
```

- [ ] **Step 2: ValidateTokenUseCase 포트 작성**

```kotlin
// apps/auth/src/main/kotlin/com/bara/auth/application/port/in/query/ValidateTokenUseCase.kt
package com.bara.auth.application.port.`in`.query

import com.bara.auth.domain.model.ValidateResult

interface ValidateTokenUseCase {
    fun validate(token: String): ValidateResult
}
```

- [ ] **Step 3: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/domain/model/ValidateResult.kt \
       apps/auth/src/main/kotlin/com/bara/auth/application/port/in/query/ValidateTokenUseCase.kt
git commit -m "feat(auth): add ValidateResult model and ValidateTokenUseCase port"
```

---

## Task 2: ValidateTokenService 구현 + 테스트

**Files:**

- Create: `apps/auth/src/main/kotlin/com/bara/auth/application/service/query/ValidateTokenService.kt`
- Create: `apps/auth/src/test/kotlin/com/bara/auth/application/service/query/ValidateTokenServiceTest.kt`

- [ ] **Step 1: ValidateTokenServiceTest 작성 (실패 테스트)**

```kotlin
// apps/auth/src/test/kotlin/com/bara/auth/application/service/query/ValidateTokenServiceTest.kt
package com.bara.auth.application.service.query

import com.bara.auth.application.port.out.ApiKeyRepository
import com.bara.auth.application.port.out.JwtClaims
import com.bara.auth.application.port.out.JwtVerifier
import com.bara.auth.application.port.out.ProviderRepository
import com.bara.auth.domain.exception.InvalidTokenException
import com.bara.auth.domain.model.ApiKey
import com.bara.auth.domain.model.Provider
import com.bara.auth.domain.model.ValidateResult
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class ValidateTokenServiceTest {

    private val jwtVerifier = mockk<JwtVerifier>()
    private val apiKeyRepository = mockk<ApiKeyRepository>()
    private val providerRepository = mockk<ProviderRepository>()
    private val sut = ValidateTokenService(jwtVerifier, apiKeyRepository, providerRepository)

    @Test
    fun `User JWT 검증 성공 시 UserResult 반환`() {
        every { jwtVerifier.verify("valid-jwt") } returns JwtClaims(
            userId = "u-1", email = "test@test.com", role = "USER"
        )

        val result = sut.validate("valid-jwt")

        assertThat(result).isEqualTo(ValidateResult.UserResult(userId = "u-1", role = "USER"))
    }

    @Test
    fun `bk_ prefix API Key 검증 성공 시 ProviderResult 반환`() {
        val apiKey = ApiKey(
            id = "k-1", providerId = "p-1", name = "test-key",
            keyHash = "somehash", keyPrefix = "bk_a3f2e1", createdAt = Instant.now(),
        )
        val provider = Provider(
            id = "p-1", userId = "u-1", name = "test-provider",
            status = Provider.ProviderStatus.ACTIVE, createdAt = Instant.now(),
        )
        every { apiKeyRepository.findByKeyHash(any()) } returns apiKey
        every { providerRepository.findById("p-1") } returns provider

        val result = sut.validate("bk_abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890ab")

        assertThat(result).isEqualTo(ValidateResult.ProviderResult(providerId = "p-1"))
    }

    @Test
    fun `bk_ prefix지만 API Key가 없으면 InvalidTokenException`() {
        every { apiKeyRepository.findByKeyHash(any()) } returns null

        assertThatThrownBy { sut.validate("bk_invalidkey") }
            .isInstanceOf(InvalidTokenException::class.java)
    }

    @Test
    fun `bk_ prefix API Key의 Provider가 ACTIVE가 아니면 InvalidTokenException`() {
        val apiKey = ApiKey(
            id = "k-1", providerId = "p-1", name = "test-key",
            keyHash = "somehash", keyPrefix = "bk_a3f2e1", createdAt = Instant.now(),
        )
        val provider = Provider(
            id = "p-1", userId = "u-1", name = "test-provider",
            status = Provider.ProviderStatus.PENDING, createdAt = Instant.now(),
        )
        every { apiKeyRepository.findByKeyHash(any()) } returns apiKey
        every { providerRepository.findById("p-1") } returns provider

        assertThatThrownBy { sut.validate("bk_somevalidkey") }
            .isInstanceOf(InvalidTokenException::class.java)
    }

    @Test
    fun `JWT 검증 실패 시 InvalidTokenException`() {
        every { jwtVerifier.verify("bad-jwt") } throws InvalidTokenException()

        assertThatThrownBy { sut.validate("bad-jwt") }
            .isInstanceOf(InvalidTokenException::class.java)
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :apps:auth:test --tests "com.bara.auth.application.service.query.ValidateTokenServiceTest"`
Expected: FAIL — ValidateTokenService 없음

- [ ] **Step 3: ValidateTokenService 구현**

```kotlin
// apps/auth/src/main/kotlin/com/bara/auth/application/service/query/ValidateTokenService.kt
package com.bara.auth.application.service.query

import com.bara.auth.application.port.`in`.query.ValidateTokenUseCase
import com.bara.auth.application.port.out.ApiKeyRepository
import com.bara.auth.application.port.out.JwtVerifier
import com.bara.auth.application.port.out.ProviderRepository
import com.bara.auth.domain.exception.InvalidTokenException
import com.bara.auth.domain.model.Provider
import com.bara.auth.domain.model.ValidateResult
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service
import java.security.MessageDigest

@Service
class ValidateTokenService(
    private val jwtVerifier: JwtVerifier,
    private val apiKeyRepository: ApiKeyRepository,
    private val providerRepository: ProviderRepository,
) : ValidateTokenUseCase {

    override fun validate(token: String): ValidateResult {
        if (token.startsWith("bk_")) {
            return validateApiKey(token)
        }
        return validateJwt(token)
    }

    private fun validateJwt(token: String): ValidateResult.UserResult {
        val claims = jwtVerifier.verify(token)
        WideEvent.put("user_id", claims.userId)
        WideEvent.put("token_type", "user_jwt")
        WideEvent.put("outcome", "token_valid")
        WideEvent.message("User JWT 검증 성공")
        return ValidateResult.UserResult(userId = claims.userId, role = claims.role)
    }

    private fun validateApiKey(rawKey: String): ValidateResult.ProviderResult {
        val keyHash = sha256(rawKey)
        val apiKey = apiKeyRepository.findByKeyHash(keyHash)
            ?: throw InvalidTokenException()

        val provider = providerRepository.findById(apiKey.providerId)
            ?: throw InvalidTokenException()

        if (provider.status != Provider.ProviderStatus.ACTIVE) {
            throw InvalidTokenException()
        }

        WideEvent.put("provider_id", provider.id)
        WideEvent.put("api_key_id", apiKey.id)
        WideEvent.put("token_type", "api_key")
        WideEvent.put("outcome", "token_valid")
        WideEvent.message("API Key 검증 성공")
        return ValidateResult.ProviderResult(providerId = provider.id)
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew :apps:auth:test --tests "com.bara.auth.application.service.query.ValidateTokenServiceTest"`
Expected: PASS (5 tests)

- [ ] **Step 5: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/application/service/query/ValidateTokenService.kt \
       apps/auth/src/test/kotlin/com/bara/auth/application/service/query/ValidateTokenServiceTest.kt
git commit -m "feat(auth): implement ValidateTokenService with JWT and API Key validation"
```

---

## Task 3: ValidateController + 테스트

**Files:**

- Create: `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ValidateController.kt`
- Create: `apps/auth/src/test/kotlin/com/bara/auth/adapter/in/rest/ValidateControllerTest.kt`

- [ ] **Step 1: ValidateControllerTest 작성 (실패 테스트)**

```kotlin
// apps/auth/src/test/kotlin/com/bara/auth/adapter/in/rest/ValidateControllerTest.kt
package com.bara.auth.adapter.`in`.rest

import com.bara.auth.application.port.`in`.query.ValidateTokenUseCase
import com.bara.auth.config.GoogleOAuthProperties
import com.bara.auth.domain.exception.InvalidTokenException
import com.bara.auth.domain.model.ValidateResult
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(controllers = [ValidateController::class])
@Import(AuthExceptionHandler::class)
@EnableConfigurationProperties(GoogleOAuthProperties::class)
@TestPropertySource(
    properties = [
        "bara.auth.google.client-id=test-client",
        "bara.auth.google.client-secret=test-secret",
        "bara.auth.google.redirect-uri=http://localhost:5173/auth/google/callback",
    ]
)
class ValidateControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var validateTokenUseCase: ValidateTokenUseCase

    @Test
    fun `User JWT 검증 성공 시 200과 X-User-Id, X-User-Role 헤더 반환`() {
        every { validateTokenUseCase.validate("valid-jwt") } returns
            ValidateResult.UserResult(userId = "u-1", role = "USER")

        mockMvc.get("/validate") {
            header("Authorization", "Bearer valid-jwt")
        }.andExpect {
            status { isOk() }
            header { string("X-User-Id", "u-1") }
            header { string("X-User-Role", "USER") }
            header { exists("X-Request-Id") }
        }
    }

    @Test
    fun `API Key 검증 성공 시 200과 X-Provider-Id 헤더 반환`() {
        every { validateTokenUseCase.validate("bk_testkey") } returns
            ValidateResult.ProviderResult(providerId = "p-1")

        mockMvc.get("/validate") {
            header("Authorization", "Bearer bk_testkey")
        }.andExpect {
            status { isOk() }
            header { string("X-Provider-Id", "p-1") }
            header { exists("X-Request-Id") }
        }
    }

    @Test
    fun `Authorization 헤더 없으면 401`() {
        mockMvc.get("/validate")
            .andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `잘못된 토큰이면 401`() {
        every { validateTokenUseCase.validate("bad-token") } throws InvalidTokenException()

        mockMvc.get("/validate") {
            header("Authorization", "Bearer bad-token")
        }.andExpect {
            status { isUnauthorized() }
        }
    }
}
```

- [ ] **Step 2: 테스트 실행 — 실패 확인**

Run: `./gradlew :apps:auth:test --tests "com.bara.auth.adapter.in.rest.ValidateControllerTest"`
Expected: FAIL — ValidateController 없음

- [ ] **Step 3: ValidateController 구현**

```kotlin
// apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ValidateController.kt
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
```

- [ ] **Step 4: 테스트 실행 — 통과 확인**

Run: `./gradlew :apps:auth:test --tests "com.bara.auth.adapter.in.rest.ValidateControllerTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ValidateController.kt \
       apps/auth/src/test/kotlin/com/bara/auth/adapter/in/rest/ValidateControllerTest.kt
git commit -m "feat(auth): add GET /validate endpoint for gateway token verification"
```

---

## Task 4: context-path 설정 + 컨트롤러 @RequestMapping 변경

**Files:**

- Modify: `apps/auth/src/main/resources/application.yml`
- Modify: `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthController.kt`
- Modify: `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/RefreshController.kt`
- Modify: `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ProviderController.kt`
- Modify: `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ApiKeyController.kt`
- Modify: `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthExceptionHandler.kt`

- [ ] **Step 1: application.yml에 context-path 추가**

`server:` 섹션에 추가:

```yaml
server:
  port: 8081
  servlet:
    context-path: /api/auth
```

- [ ] **Step 2: AuthController — @RequestMapping 변경 + 리다이렉트 수정**

```kotlin
@RestController
@RequestMapping("/google")  // was "/auth/google"
class AuthController(
    private val useCase: LoginWithGoogleUseCase,
    private val googleProps: GoogleOAuthProperties,
) {
    // login(), callback() 그대로 유지

    private fun frontendCallbackBase(): String =
        googleProps.redirectUri.replace("/api/auth/google/callback", "/auth/callback")
}
```

- [ ] **Step 3: RefreshController — @RequestMapping 변경**

```kotlin
@RestController  // @RequestMapping("/auth") 제거
class RefreshController(
    private val useCase: RefreshTokenUseCase,
) {
    @PostMapping("/refresh")  // context-path + /refresh = /api/auth/refresh
    fun refresh(...) { ... }
}
```

- [ ] **Step 4: ProviderController — @RequestMapping 변경**

```kotlin
@RestController
@RequestMapping("/provider")  // was "/auth/provider"
class ProviderController(
    ...
)
```

- [ ] **Step 5: ApiKeyController — @RequestMapping 변경**

```kotlin
@RestController
@RequestMapping("/provider/api-key")  // was "/auth/provider/api-key"
class ApiKeyController(
    ...
)
```

- [ ] **Step 6: AuthExceptionHandler — 리다이렉트 경로 수정**

```kotlin
private fun frontendCallbackBase(): String {
    return googleProps.redirectUri.replace("/api/auth/google/callback", "/auth/callback")
}
```

- [ ] **Step 7: health probe 경로 확인**

context-path 적용 시 actuator 경로도 `/api/auth/actuator/health`가 된다. `application.yml`에 management 경로를 별도 설정:

```yaml
management:
  server:
    base-path: /
  endpoints:
    web:
      base-path: /actuator
```

또는 management port를 별도로 분리. 가장 간단한 방법은 management base-path를 context-path 밖으로 빼는 것:

```yaml
management:
  endpoints:
    web:
      base-path: /actuator
  server:
    port: 8082
```

대신 K8s health probe가 8082 포트를 바라보도록 수정해야 한다. 더 간단한 방법으로 actuator의 `base-path`를 절대 경로로 설정:

실제로는 Spring Boot에서 `server.servlet.context-path`는 actuator에도 적용된다. K8s probe 경로만 `/api/auth/actuator/health/readiness`로 변경하면 된다.

- [ ] **Step 8: 전체 테스트 실행**

Run: `./gradlew :apps:auth:test`

이 시점에서 기존 테스트들의 URL 경로가 맞지 않아 실패할 수 있다. WebMvcTest는 context-path를 무시하므로 기존 경로 그대로 동작해야 한다. 확인 후 실패하는 테스트가 있으면 수정.

Expected: ALL PASS (WebMvcTest는 context-path 무시)

- [ ] **Step 9: 커밋**

```bash
git add apps/auth/src/main/resources/application.yml \
       apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthController.kt \
       apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/RefreshController.kt \
       apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ProviderController.kt \
       apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ApiKeyController.kt \
       apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthExceptionHandler.kt
git commit -m "refactor(auth): migrate to /api/auth context-path"
```

---

## Task 5: 컨트롤러 리팩터링 — JwtVerifier → X-User-Id 헤더

**Files:**

- Modify: `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ProviderController.kt`
- Modify: `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ApiKeyController.kt`
- Modify: `apps/auth/src/test/kotlin/com/bara/auth/adapter/in/rest/ProviderControllerTest.kt`
- Modify: `apps/auth/src/test/kotlin/com/bara/auth/adapter/in/rest/ApiKeyControllerTest.kt`

- [ ] **Step 1: ProviderController에서 JwtVerifier 제거**

```kotlin
@RestController
@RequestMapping("/provider")
class ProviderController(
    private val registerUseCase: RegisterProviderUseCase,
    private val getProviderQuery: GetProviderQuery,
    // JwtVerifier 제거
) {
    @GetMapping
    fun get(
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

    @PostMapping("/register")
    fun register(
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
    // extractUserId() 삭제
}
```

- [ ] **Step 2: ApiKeyController에서 JwtVerifier 제거**

모든 메서드에서:

```kotlin
// Before
@RequestHeader("Authorization") auth: String,
// ...
val userId = extractUserId(auth)

// After
@RequestHeader("X-User-Id") userId: String,
```

생성자에서 `jwtVerifier` 제거, `extractUserId()` 삭제.

- [ ] **Step 3: ProviderControllerTest 업데이트**

- `@MockkBean lateinit var jwtVerifier: JwtVerifier` 제거
- 모든 `every { jwtVerifier.verify(...) }` 삭제
- `header("Authorization", "Bearer test-jwt")` → `header("X-User-Id", "u-1")`

```kotlin
@Test
fun `POST provider register 성공 시 201과 Provider 정보 반환`() {
    val now = Instant.parse("2024-01-01T00:00:00Z")
    every { registerUseCase.register("u-1", "my-provider") } returns Provider(
        id = "p-1", userId = "u-1", name = "my-provider",
        status = Provider.ProviderStatus.PENDING, createdAt = now,
    )

    mockMvc.post("/provider/register") {
        header("X-User-Id", "u-1")
        contentType = MediaType.APPLICATION_JSON
        content = """{"name":"my-provider"}"""
    }.andExpect {
        status { isCreated() }
        jsonPath("$.id") { value("p-1") }
    }
}
```

- [ ] **Step 4: ApiKeyControllerTest 업데이트**

- `@MockkBean lateinit var jwtVerifier: JwtVerifier` 제거
- `stubJwt()` 함수 삭제, 모든 호출 제거
- `header("Authorization", "Bearer test-jwt")` → `header("X-User-Id", "u-1")`
- URL 경로: `/auth/provider/api-key` → `/provider/api-key`

- [ ] **Step 5: 전체 테스트 통과 확인**

Run: `./gradlew :apps:auth:test`
Expected: ALL PASS

- [ ] **Step 6: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ProviderController.kt \
       apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ApiKeyController.kt \
       apps/auth/src/test/kotlin/com/bara/auth/adapter/in/rest/ProviderControllerTest.kt \
       apps/auth/src/test/kotlin/com/bara/auth/adapter/in/rest/ApiKeyControllerTest.kt
git commit -m "refactor(auth): replace JwtVerifier with X-User-Id header in controllers"
```

---

## Task 6: FE URL 변경 + Vite proxy

**Files:**

- Modify: `apps/fe/src/pages/LoginPage.tsx`
- Modify: `apps/fe/src/pages/ProviderPage.tsx`
- Modify: `apps/fe/src/lib/api.ts`
- Modify: `apps/fe/vite.config.ts`

- [ ] **Step 1: LoginPage.tsx — href 변경**

```typescript
href = '/api/auth/google/login'; // was "/auth/google/login"
```

- [ ] **Step 2: ProviderPage.tsx — 모든 URL 변경**

`/auth/provider` → `/api/auth/provider` (전체 replace)
`/auth/provider/register` → `/api/auth/provider/register`
`/auth/provider/api-key` → `/api/auth/provider/api-key`

- [ ] **Step 3: api.ts — refresh URL 변경**

```typescript
const res = await fetch('/api/auth/refresh', {  // was '/auth/refresh'
```

- [ ] **Step 4: vite.config.ts — proxy 변경**

```typescript
proxy: {
  '/api/auth': {
    target: 'http://localhost:8081',
    changeOrigin: true,
  },
},
```

- [ ] **Step 5: FE 테스트 + 빌드 확인**

Run: `cd apps/fe && pnpm test && pnpm build`
Expected: ALL PASS, 빌드 성공

- [ ] **Step 6: 커밋**

```bash
git add apps/fe/src/pages/LoginPage.tsx \
       apps/fe/src/pages/ProviderPage.tsx \
       apps/fe/src/lib/api.ts \
       apps/fe/vite.config.ts
git commit -m "refactor(fe): migrate API URLs to /api/auth path"
```

---

## Task 7: K8s Traefik 미들웨어 + 라우팅

**Files:**

- Create: `infra/k8s/base/gateway/middlewares.yaml`
- Modify: `infra/k8s/base/gateway/routes.yaml`
- Modify: `infra/k8s/base/gateway/traefik-config.yaml`
- Modify: `infra/k8s/base/core/auth.yaml`

- [ ] **Step 1: Traefik CRD 활성화**

`traefik-config.yaml`에 CRD provider 추가:

```yaml
apiVersion: helm.cattle.io/v1
kind: HelmChartConfig
metadata:
  name: traefik
  namespace: kube-system
spec:
  valuesContent: |-
    providers:
      kubernetesGateway:
        enabled: true
      kubernetesCRD:
        enabled: true
        allowCrossNamespace: true
    gateway:
      listeners:
        web:
          namespacePolicy:
            from: All
```

- [ ] **Step 2: middlewares.yaml 생성**

```yaml
# infra/k8s/base/gateway/middlewares.yaml
apiVersion: traefik.io/v1alpha1
kind: Middleware
metadata:
  name: auth-forward
  namespace: core
spec:
  forwardAuth:
    address: http://auth.core.svc.cluster.local:8081/api/auth/validate
    authResponseHeaders:
      - X-User-Id
      - X-User-Role
      - X-Provider-Id
      - X-Request-Id
---
apiVersion: traefik.io/v1alpha1
kind: Middleware
metadata:
  name: cors
  namespace: core
spec:
  headers:
    accessControlAllowOriginList:
      - 'http://localhost'
      - 'https://baraworlds.com'
    accessControlAllowMethods:
      - GET
      - POST
      - PATCH
      - DELETE
      - OPTIONS
    accessControlAllowHeaders:
      - Authorization
      - Content-Type
    accessControlMaxAge: 86400
```

- [ ] **Step 3: routes.yaml 재작성**

Traefik Gateway API + CRD Middleware를 함께 사용하기 위해 IngressRoute CRD로 전환:

```yaml
# infra/k8s/base/gateway/routes.yaml
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: auth-public
  namespace: core
spec:
  entryPoints:
    - web
  routes:
    - match: PathPrefix(`/api/auth/google`) || Path(`/api/auth/refresh`) || Path(`/api/auth/validate`)
      kind: Rule
      middlewares:
        - name: cors
          namespace: core
      services:
        - name: auth
          port: 8081
---
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: auth-protected
  namespace: core
spec:
  entryPoints:
    - web
  routes:
    - match: PathPrefix(`/api/auth/provider`)
      kind: Rule
      middlewares:
        - name: auth-forward
          namespace: core
        - name: cors
          namespace: core
      services:
        - name: auth
          port: 8081
---
apiVersion: traefik.io/v1alpha1
kind: IngressRoute
metadata:
  name: fe-route
  namespace: core
spec:
  entryPoints:
    - web
  routes:
    - match: PathPrefix(`/`)
      kind: Rule
      priority: 1
      services:
        - name: fe
          port: 5173
```

- [ ] **Step 4: auth.yaml — health probe 경로 수정**

```yaml
readinessProbe:
  httpGet:
    path: /api/auth/actuator/health/readiness
    port: 8081
livenessProbe:
  httpGet:
    path: /api/auth/actuator/health/liveness
    port: 8081
```

- [ ] **Step 5: kustomization.yaml에 middlewares.yaml 포함 확인**

`infra/k8s/base/kustomization.yaml`에 `gateway/middlewares.yaml`이 resources에 포함되는지 확인하고 추가.

- [ ] **Step 6: 커밋**

```bash
git add infra/k8s/base/gateway/middlewares.yaml \
       infra/k8s/base/gateway/routes.yaml \
       infra/k8s/base/gateway/traefik-config.yaml \
       infra/k8s/base/core/auth.yaml
git commit -m "feat(infra): add Traefik forwardAuth + CORS middlewares and route migration"
```

---

## Task 8: 문서 업데이트 + .env 변경

**Files:**

- Modify: `docs/spec/auth/authentication.md`
- Modify: `docs/spec/shared/security.md`
- Modify: `CLAUDE.md`
- Modify: `.env` (수동 — Google redirect URI)
- Modify: `docs/guides/auth-local-setup.md`

- [ ] **Step 1: authentication.md — 엔드포인트 테이블 경로 변경**

모든 엔드포인트 경로에 `/api/auth` prefix 추가. `GET /api/auth/validate` 행 추가.

- [ ] **Step 2: security.md — "Nginx 헤더 주입" → "Traefik forwardAuth 헤더 주입"**

섹션 제목과 내용을 Traefik forwardAuth로 업데이트.

- [ ] **Step 3: CLAUDE.md — Auth Service 경로 변경 반영**

`bootRun` 설명에서 8081 포트 + `/api/auth` context-path 언급.

- [ ] **Step 4: auth-local-setup.md — redirect URI 변경**

`http://localhost/api/auth/google/callback`으로 변경.

- [ ] **Step 5: 커밋**

```bash
git add docs/spec/auth/authentication.md \
       docs/spec/shared/security.md \
       CLAUDE.md \
       docs/guides/auth-local-setup.md
git commit -m "docs(docs): update specs for /api/auth route and Traefik forwardAuth"
```

---

## Task 9: 전체 빌드 + 수동 테스트

- [ ] **Step 1: 전체 테스트 통과 확인**

```bash
./gradlew :apps:auth:test
cd apps/fe && pnpm test && pnpm build
```

Expected: ALL PASS

- [ ] **Step 2: .env에서 BARA_AUTH_GOOGLE_REDIRECT_URI 변경**

```
BARA_AUTH_GOOGLE_REDIRECT_URI=http://localhost/api/auth/google/callback
```

Google Cloud Console에서도 redirect URI 업데이트.

- [ ] **Step 3: 로컬 실행 테스트**

```bash
./scripts/infra.sh up
./gradlew :apps:auth:bootRun &
cd apps/fe && pnpm dev
```

수동 확인:

1. `http://localhost:5173/` → Google 로그인 동작 (redirect URI 변경됨)
2. Provider 설정 페이지 동작 (X-User-Id 헤더 — 로컬에서는 Vite proxy가 직접 백엔드로 보내므로 forwardAuth 없이 동작 확인 불가. 테스트용으로 수동 헤더 전달 필요)

- [ ] **Step 4: K8s 클러스터 테스트 (선택)**

```bash
./scripts/docker.sh build
./scripts/k8s.sh create
```

K8s 환경에서 forwardAuth가 정상 동작하는지 확인.

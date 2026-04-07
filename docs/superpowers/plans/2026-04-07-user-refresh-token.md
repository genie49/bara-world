# User Access/Refresh Token Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 단일 JWT(1h)를 Access Token(1h) + Refresh Token(7d, Redis, Rotation + 재사용 감지) 방식으로 변경한다.

**Architecture:** 기존 `JwtIssuer` 포트는 Access Token 발급 용도로 유지하고, `RefreshTokenIssuer`/`RefreshTokenStore` 포트를 새로 추가한다. `LoginWithGoogleService`의 반환 타입을 `String`(JWT)에서 `TokenPair`(access + refresh)로 변경하고, `POST /auth/refresh` 엔드포인트를 추가한다.

**Tech Stack:** Kotlin, Spring Boot, Auth0 java-jwt, Redis (StringRedisTemplate), MockK

**Spec:** `docs/superpowers/specs/2026-04-07-provider-auth-design.md` 섹션 1

---

## File Structure

### 새로 생성

| 파일 | 책임 |
|------|------|
| `domain/model/TokenPair.kt` | Access + Refresh 토큰 쌍 값 객체 |
| `application/port/in/command/RefreshTokenUseCase.kt` | Refresh 토큰 갱신 유스케이스 인터페이스 |
| `application/port/out/RefreshTokenIssuer.kt` | Refresh Token JWT 발급 포트 |
| `application/port/out/RefreshTokenStore.kt` | Redis Refresh Token 저장/조회/삭제 포트 |
| `application/service/command/RefreshTokenService.kt` | Rotation + 재사용 감지 서비스 |
| `adapter/in/rest/RefreshController.kt` | `POST /auth/refresh` 엔드포인트 |
| `adapter/out/external/RefreshTokenJwtAdapter.kt` | Refresh Token JWT 발급/검증 어댑터 |
| `adapter/out/cache/RefreshTokenRedisStore.kt` | Redis Refresh Token 저장소 어댑터 |
| `config/RefreshTokenProperties.kt` | Refresh Token 설정 |

### 수정

| 파일 | 변경 내용 |
|------|-----------|
| `application/port/in/command/LoginWithGoogleUseCase.kt` | `login()` 반환 타입 `String` → `TokenPair` |
| `application/service/command/LoginWithGoogleService.kt` | Refresh Token 발급 + Redis 저장 추가 |
| `adapter/in/rest/AuthController.kt` | 콜백 리다이렉트에 refreshToken 추가 |
| `application.yml` | `bara.auth.refresh-token` 설정 추가 |

### 테스트

| 파일 | 대상 |
|------|------|
| `test/.../service/command/RefreshTokenServiceTest.kt` | Rotation, 재사용 감지, Grace Period |
| `test/.../service/command/LoginWithGoogleServiceTest.kt` | TokenPair 반환 검증 (기존 테스트 수정) |
| `test/.../adapter/in/rest/RefreshControllerTest.kt` | `/auth/refresh` 엔드포인트 |
| `test/.../adapter/in/rest/AuthControllerTest.kt` | 콜백 응답에 refreshToken 포함 (기존 테스트 수정) |
| `test/.../adapter/out/external/RefreshTokenJwtAdapterTest.kt` | Refresh JWT 발급/검증 |
| `test/.../adapter/out/cache/RefreshTokenRedisStoreTest.kt` | Redis 저장/조회/삭제 |

> 모든 파일 경로의 루트는 `apps/auth/src/main/kotlin/com/bara/auth/` (소스) 또는 `apps/auth/src/test/kotlin/com/bara/auth/` (테스트)

---

### Task 1: TokenPair 도메인 모델 + UseCase 인터페이스 변경

**Files:**
- Create: `apps/auth/src/main/kotlin/com/bara/auth/domain/model/TokenPair.kt`
- Modify: `apps/auth/src/main/kotlin/com/bara/auth/application/port/in/command/LoginWithGoogleUseCase.kt`
- Create: `apps/auth/src/main/kotlin/com/bara/auth/application/port/in/command/RefreshTokenUseCase.kt`

- [ ] **Step 1: TokenPair 도메인 모델 생성**

```kotlin
// apps/auth/src/main/kotlin/com/bara/auth/domain/model/TokenPair.kt
package com.bara.auth.domain.model

data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
)
```

- [ ] **Step 2: LoginWithGoogleUseCase 반환 타입 변경**

```kotlin
// apps/auth/src/main/kotlin/com/bara/auth/application/port/in/command/LoginWithGoogleUseCase.kt
package com.bara.auth.application.port.in.command

import com.bara.auth.domain.model.TokenPair

interface LoginWithGoogleUseCase {
    fun login(code: String, state: String): TokenPair
    fun buildLoginUrl(): String
}
```

- [ ] **Step 3: RefreshTokenUseCase 생성**

```kotlin
// apps/auth/src/main/kotlin/com/bara/auth/application/port/in/command/RefreshTokenUseCase.kt
package com.bara.auth.application.port.in.command

import com.bara.auth.domain.model.TokenPair

interface RefreshTokenUseCase {
    fun refresh(refreshToken: String): TokenPair
}
```

- [ ] **Step 4: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/domain/model/TokenPair.kt \
        apps/auth/src/main/kotlin/com/bara/auth/application/port/in/command/LoginWithGoogleUseCase.kt \
        apps/auth/src/main/kotlin/com/bara/auth/application/port/in/command/RefreshTokenUseCase.kt
git commit -m "feat(auth): add TokenPair model and RefreshTokenUseCase port"
```

---

### Task 2: RefreshToken 포트 + Properties 정의

**Files:**
- Create: `apps/auth/src/main/kotlin/com/bara/auth/application/port/out/RefreshTokenIssuer.kt`
- Create: `apps/auth/src/main/kotlin/com/bara/auth/application/port/out/RefreshTokenStore.kt`
- Create: `apps/auth/src/main/kotlin/com/bara/auth/config/RefreshTokenProperties.kt`
- Modify: `apps/auth/src/main/resources/application.yml`

- [ ] **Step 1: RefreshTokenIssuer 포트 생성**

```kotlin
// apps/auth/src/main/kotlin/com/bara/auth/application/port/out/RefreshTokenIssuer.kt
package com.bara.auth.application.port.out

data class RefreshTokenClaims(
    val userId: String,
    val jti: String,
    val family: String,
)

interface RefreshTokenIssuer {
    fun issue(userId: String, family: String): String
    fun verify(token: String): RefreshTokenClaims
}
```

- [ ] **Step 2: RefreshTokenStore 포트 생성**

```kotlin
// apps/auth/src/main/kotlin/com/bara/auth/application/port/out/RefreshTokenStore.kt
package com.bara.auth.application.port.out

interface RefreshTokenStore {
    fun save(userId: String, jti: String, family: String)
    fun find(userId: String): StoredRefreshToken?
    fun delete(userId: String)
    fun saveGrace(jti: String)
    fun isGraceValid(jti: String): Boolean
}

data class StoredRefreshToken(
    val jti: String,
    val family: String,
)
```

- [ ] **Step 3: RefreshTokenProperties 생성**

```kotlin
// apps/auth/src/main/kotlin/com/bara/auth/config/RefreshTokenProperties.kt
package com.bara.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "bara.auth.refresh-token")
data class RefreshTokenProperties(
    val audience: String,
    val expirySeconds: Long,
    val gracePeriodSeconds: Long,
)
```

- [ ] **Step 4: application.yml에 refresh-token 설정 추가**

`application.yml`의 `bara.auth` 섹션 끝에 추가:

```yaml
  refresh-token:
    audience: bara-refresh
    expiry-seconds: 604800  # 7일
    grace-period-seconds: 30
```

- [ ] **Step 5: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/application/port/out/RefreshTokenIssuer.kt \
        apps/auth/src/main/kotlin/com/bara/auth/application/port/out/RefreshTokenStore.kt \
        apps/auth/src/main/kotlin/com/bara/auth/config/RefreshTokenProperties.kt \
        apps/auth/src/main/resources/application.yml
git commit -m "feat(auth): add RefreshToken ports and properties"
```

---

### Task 3: RefreshTokenJwtAdapter 구현 + 테스트

**Files:**
- Create: `apps/auth/src/main/kotlin/com/bara/auth/adapter/out/external/RefreshTokenJwtAdapter.kt`
- Create: `apps/auth/src/test/kotlin/com/bara/auth/adapter/out/external/RefreshTokenJwtAdapterTest.kt`

- [ ] **Step 1: 테스트 작성**

```kotlin
// apps/auth/src/test/kotlin/com/bara/auth/adapter/out/external/RefreshTokenJwtAdapterTest.kt
package com.bara.auth.adapter.out.external

import com.bara.auth.config.JwtProperties
import com.bara.auth.config.RefreshTokenProperties
import com.bara.auth.domain.exception.InvalidTokenException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.KeyPairGenerator
import java.util.Base64

class RefreshTokenJwtAdapterTest {

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private val jwtProps = JwtProperties(
        issuer = "bara-auth",
        audience = "bara-world",
        expirySeconds = 3600,
        privateKeyBase64 = Base64.getEncoder().encodeToString(toPem(keyPair.private.encoded, "PRIVATE KEY").toByteArray()),
        publicKeyBase64 = Base64.getEncoder().encodeToString(toPem(keyPair.public.encoded, "PUBLIC KEY").toByteArray()),
    )

    private val refreshProps = RefreshTokenProperties(
        audience = "bara-refresh",
        expirySeconds = 604800,
        gracePeriodSeconds = 30,
    )

    private val adapter = RefreshTokenJwtAdapter(jwtProps, refreshProps)

    @Test
    fun `발급한 Refresh 토큰을 검증하면 클레임이 반환된다`() {
        val token = adapter.issue("user-123", "family-abc")
        val claims = adapter.verify(token)

        assertEquals("user-123", claims.userId)
        assertEquals("family-abc", claims.family)
        assertTrue(claims.jti.isNotBlank())
    }

    @Test
    fun `Access Token audience로 발급된 JWT는 검증에 실패한다`() {
        val accessAdapter = Rs256JwtAdapter(jwtProps)
        val user = com.bara.auth.domain.model.User(
            id = "user-123", googleId = "g-1", email = "a@b.com",
            name = "Test", role = com.bara.auth.domain.model.User.Role.USER,
            createdAt = java.time.Instant.now(),
        )
        val accessToken = accessAdapter.issue(user)

        assertThrows<InvalidTokenException> { adapter.verify(accessToken) }
    }

    @Test
    fun `임의의 문자열은 InvalidTokenException을 던진다`() {
        assertThrows<InvalidTokenException> { adapter.verify("not-a-jwt") }
    }

    private fun toPem(der: ByteArray, label: String): String {
        val b64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return "-----BEGIN $label-----\n$b64\n-----END $label-----"
    }
}
```

- [ ] **Step 2: 테스트 실행하여 실패 확인**

Run: `./gradlew :apps:auth:test --tests "*.RefreshTokenJwtAdapterTest" -x :libs:common:compileKotlin`
Expected: FAIL (class not found)

- [ ] **Step 3: RefreshTokenJwtAdapter 구현**

```kotlin
// apps/auth/src/main/kotlin/com/bara/auth/adapter/out/external/RefreshTokenJwtAdapter.kt
package com.bara.auth.adapter.out.external

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.bara.auth.application.port.out.RefreshTokenClaims
import com.bara.auth.application.port.out.RefreshTokenIssuer
import com.bara.auth.config.JwtProperties
import com.bara.auth.config.RefreshTokenProperties
import com.bara.auth.domain.exception.InvalidTokenException
import org.springframework.stereotype.Component
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*

@Component
class RefreshTokenJwtAdapter(
    private val jwtProps: JwtProperties,
    private val refreshProps: RefreshTokenProperties,
) : RefreshTokenIssuer {

    private val algorithm: Algorithm by lazy {
        val privateKey = loadPrivateKey(jwtProps.privateKeyPem())
        val publicKey = loadPublicKey(jwtProps.publicKeyPem())
        Algorithm.RSA256(publicKey, privateKey)
    }

    private val verifier by lazy {
        JWT.require(algorithm)
            .withIssuer(jwtProps.issuer)
            .withAudience(refreshProps.audience)
            .build()
    }

    override fun issue(userId: String, family: String): String {
        val now = java.time.Instant.now()
        return JWT.create()
            .withIssuer(jwtProps.issuer)
            .withAudience(refreshProps.audience)
            .withSubject(userId)
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(refreshProps.expirySeconds)))
            .withClaim("family", family)
            .sign(algorithm)
    }

    override fun verify(token: String): RefreshTokenClaims {
        try {
            val decoded = verifier.verify(token)
            return RefreshTokenClaims(
                userId = decoded.subject,
                jti = decoded.id,
                family = decoded.getClaim("family").asString(),
            )
        } catch (e: Exception) {
            throw InvalidTokenException()
        }
    }

    private fun loadPrivateKey(pem: String): RSAPrivateKey {
        val spec = PKCS8EncodedKeySpec(pemToDer(pem))
        return KeyFactory.getInstance("RSA").generatePrivate(spec) as RSAPrivateKey
    }

    private fun loadPublicKey(pem: String): RSAPublicKey {
        val spec = X509EncodedKeySpec(pemToDer(pem))
        return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
    }

    private fun pemToDer(pem: String): ByteArray {
        val body = pem.lines()
            .filter { !it.startsWith("-----") }
            .joinToString("")
        return Base64.getDecoder().decode(body)
    }
}
```

- [ ] **Step 4: 테스트 실행하여 통과 확인**

Run: `./gradlew :apps:auth:test --tests "*.RefreshTokenJwtAdapterTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/adapter/out/external/RefreshTokenJwtAdapter.kt \
        apps/auth/src/test/kotlin/com/bara/auth/adapter/out/external/RefreshTokenJwtAdapterTest.kt
git commit -m "feat(auth): implement RefreshTokenJwtAdapter with RS256"
```

---

### Task 4: RefreshTokenRedisStore 구현 + 테스트

**Files:**
- Create: `apps/auth/src/main/kotlin/com/bara/auth/adapter/out/cache/RefreshTokenRedisStore.kt`
- Create: `apps/auth/src/test/kotlin/com/bara/auth/adapter/out/cache/RefreshTokenRedisStoreTest.kt`

- [ ] **Step 1: 테스트 작성**

```kotlin
// apps/auth/src/test/kotlin/com/bara/auth/adapter/out/cache/RefreshTokenRedisStoreTest.kt
package com.bara.auth.adapter.out.cache

import com.bara.auth.config.RefreshTokenProperties
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

class RefreshTokenRedisStoreTest {

    private val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
    private val template = mockk<StringRedisTemplate> {
        every { opsForValue() } returns valueOps
        every { delete(any<String>()) } returns true
    }
    private val props = RefreshTokenProperties(
        audience = "bara-refresh",
        expirySeconds = 604800,
        gracePeriodSeconds = 30,
    )
    private val store = RefreshTokenRedisStore(template, props)

    @Test
    fun `save는 userId 키로 jti와 family를 저장한다`() {
        store.save("user-1", "jti-abc", "family-xyz")

        verify {
            valueOps.set(
                "refresh:user-1",
                """{"jti":"jti-abc","family":"family-xyz"}""",
                Duration.ofSeconds(604800),
            )
        }
    }

    @Test
    fun `find는 저장된 토큰 정보를 반환한다`() {
        every { valueOps.get("refresh:user-1") } returns """{"jti":"jti-abc","family":"family-xyz"}"""

        val result = store.find("user-1")

        assertNotNull(result)
        assertEquals("jti-abc", result!!.jti)
        assertEquals("family-xyz", result.family)
    }

    @Test
    fun `find는 저장된 값이 없으면 null을 반환한다`() {
        every { valueOps.get("refresh:user-1") } returns null

        assertNull(store.find("user-1"))
    }

    @Test
    fun `delete는 userId 키를 삭제한다`() {
        store.delete("user-1")

        verify { template.delete("refresh:user-1") }
    }

    @Test
    fun `saveGrace는 이전 jti를 grace period TTL로 저장한다`() {
        store.saveGrace("old-jti")

        verify {
            valueOps.set("refresh:grace:old-jti", "valid", Duration.ofSeconds(30))
        }
    }

    @Test
    fun `isGraceValid는 grace 키가 있으면 true를 반환한다`() {
        every { template.hasKey("refresh:grace:old-jti") } returns true

        assertTrue(store.isGraceValid("old-jti"))
    }

    @Test
    fun `isGraceValid는 grace 키가 없으면 false를 반환한다`() {
        every { template.hasKey("refresh:grace:old-jti") } returns false

        assertFalse(store.isGraceValid("old-jti"))
    }
}
```

- [ ] **Step 2: 테스트 실행하여 실패 확인**

Run: `./gradlew :apps:auth:test --tests "*.RefreshTokenRedisStoreTest"`
Expected: FAIL (class not found)

- [ ] **Step 3: RefreshTokenRedisStore 구현**

```kotlin
// apps/auth/src/main/kotlin/com/bara/auth/adapter/out/cache/RefreshTokenRedisStore.kt
package com.bara.auth.adapter.out.cache

import com.bara.auth.application.port.out.RefreshTokenStore
import com.bara.auth.application.port.out.StoredRefreshToken
import com.bara.auth.config.RefreshTokenProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class RefreshTokenRedisStore(
    private val redis: StringRedisTemplate,
    private val props: RefreshTokenProperties,
) : RefreshTokenStore {

    private val mapper = jacksonObjectMapper()

    override fun save(userId: String, jti: String, family: String) {
        val value = mapper.writeValueAsString(mapOf("jti" to jti, "family" to family))
        redis.opsForValue().set(keyOf(userId), value, Duration.ofSeconds(props.expirySeconds))
    }

    override fun find(userId: String): StoredRefreshToken? {
        val json = redis.opsForValue().get(keyOf(userId)) ?: return null
        val map: Map<String, String> = mapper.readValue(json)
        return StoredRefreshToken(jti = map["jti"]!!, family = map["family"]!!)
    }

    override fun delete(userId: String) {
        redis.delete(keyOf(userId))
    }

    override fun saveGrace(jti: String) {
        redis.opsForValue().set(graceKeyOf(jti), "valid", Duration.ofSeconds(props.gracePeriodSeconds))
    }

    override fun isGraceValid(jti: String): Boolean {
        return redis.hasKey(graceKeyOf(jti)) == true
    }

    private fun keyOf(userId: String) = "refresh:$userId"
    private fun graceKeyOf(jti: String) = "refresh:grace:$jti"
}
```

- [ ] **Step 4: 테스트 실행하여 통과 확인**

Run: `./gradlew :apps:auth:test --tests "*.RefreshTokenRedisStoreTest"`
Expected: PASS (7 tests)

- [ ] **Step 5: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/adapter/out/cache/RefreshTokenRedisStore.kt \
        apps/auth/src/test/kotlin/com/bara/auth/adapter/out/cache/RefreshTokenRedisStoreTest.kt
git commit -m "feat(auth): implement RefreshTokenRedisStore with rotation support"
```

---

### Task 5: RefreshTokenService 구현 + 테스트

**Files:**
- Create: `apps/auth/src/main/kotlin/com/bara/auth/application/service/command/RefreshTokenService.kt`
- Create: `apps/auth/src/test/kotlin/com/bara/auth/application/service/command/RefreshTokenServiceTest.kt`

- [ ] **Step 1: 테스트 작성**

```kotlin
// apps/auth/src/test/kotlin/com/bara/auth/application/service/command/RefreshTokenServiceTest.kt
package com.bara.auth.application.service.command

import com.bara.auth.application.port.out.*
import com.bara.auth.domain.exception.InvalidTokenException
import io.mockk.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RefreshTokenServiceTest {

    private val refreshTokenIssuer = mockk<RefreshTokenIssuer>()
    private val refreshTokenStore = mockk<RefreshTokenStore>(relaxed = true)
    private val jwtIssuer = mockk<JwtIssuer>()
    private val userRepository = mockk<UserRepository>()

    private val service = RefreshTokenService(
        refreshTokenIssuer, refreshTokenStore, jwtIssuer, userRepository,
    )

    private val user = com.bara.auth.domain.model.User(
        id = "user-1", googleId = "g-1", email = "test@test.com",
        name = "Test", role = com.bara.auth.domain.model.User.Role.USER,
        createdAt = java.time.Instant.now(),
    )

    @Test
    fun `유효한 Refresh Token으로 새 Access와 Refresh가 발급된다`() {
        val claims = RefreshTokenClaims(userId = "user-1", jti = "jti-1", family = "fam-1")
        every { refreshTokenIssuer.verify("old-refresh") } returns claims
        every { refreshTokenStore.find("user-1") } returns StoredRefreshToken(jti = "jti-1", family = "fam-1")
        every { userRepository.findById("user-1") } returns user
        every { jwtIssuer.issue(user) } returns "new-access"
        every { refreshTokenIssuer.issue("user-1", "fam-1") } returns "new-refresh"

        val result = service.refresh("old-refresh")

        assertEquals("new-access", result.accessToken)
        assertEquals("new-refresh", result.refreshToken)
        verify { refreshTokenStore.saveGrace("jti-1") }
        verify { refreshTokenStore.save("user-1", any(), "fam-1") }
    }

    @Test
    fun `JTI 불일치 시 재사용 감지로 family 전체 무효화`() {
        val claims = RefreshTokenClaims(userId = "user-1", jti = "stolen-jti", family = "fam-1")
        every { refreshTokenIssuer.verify("stolen-refresh") } returns claims
        every { refreshTokenStore.find("user-1") } returns StoredRefreshToken(jti = "current-jti", family = "fam-1")
        every { refreshTokenStore.isGraceValid("stolen-jti") } returns false

        assertThrows<InvalidTokenException> { service.refresh("stolen-refresh") }
        verify { refreshTokenStore.delete("user-1") }
    }

    @Test
    fun `JTI 불일치이지만 Grace Period 내면 허용된다`() {
        val claims = RefreshTokenClaims(userId = "user-1", jti = "old-jti", family = "fam-1")
        every { refreshTokenIssuer.verify("old-refresh") } returns claims
        every { refreshTokenStore.find("user-1") } returns StoredRefreshToken(jti = "new-jti", family = "fam-1")
        every { refreshTokenStore.isGraceValid("old-jti") } returns true
        every { userRepository.findById("user-1") } returns user
        every { jwtIssuer.issue(user) } returns "new-access"
        every { refreshTokenIssuer.issue("user-1", "fam-1") } returns "new-refresh"

        val result = service.refresh("old-refresh")

        assertEquals("new-access", result.accessToken)
    }

    @Test
    fun `Redis에 저장된 토큰이 없으면 InvalidTokenException`() {
        val claims = RefreshTokenClaims(userId = "user-1", jti = "jti-1", family = "fam-1")
        every { refreshTokenIssuer.verify("refresh") } returns claims
        every { refreshTokenStore.find("user-1") } returns null

        assertThrows<InvalidTokenException> { service.refresh("refresh") }
    }

    @Test
    fun `User가 존재하지 않으면 InvalidTokenException`() {
        val claims = RefreshTokenClaims(userId = "user-1", jti = "jti-1", family = "fam-1")
        every { refreshTokenIssuer.verify("refresh") } returns claims
        every { refreshTokenStore.find("user-1") } returns StoredRefreshToken(jti = "jti-1", family = "fam-1")
        every { userRepository.findById("user-1") } returns null

        assertThrows<InvalidTokenException> { service.refresh("refresh") }
    }
}
```

- [ ] **Step 2: 테스트 실행하여 실패 확인**

Run: `./gradlew :apps:auth:test --tests "*.RefreshTokenServiceTest"`
Expected: FAIL (class not found)

- [ ] **Step 3: UserRepository 포트에 findById 추가**

기존 `UserRepository`에 `findById` 메서드가 없으면 추가한다.

```kotlin
// apps/auth/src/main/kotlin/com/bara/auth/application/port/out/UserRepository.kt
package com.bara.auth.application.port.out

import com.bara.auth.domain.model.User

interface UserRepository {
    fun findByGoogleId(googleId: String): User?
    fun findById(id: String): User?
    fun save(user: User): User
}
```

해당 MongoDB 어댑터(`UserMongoRepository`)와 Spring Data 인터페이스(`UserMongoDataRepository`)에도 `findById` 구현을 추가한다.

- [ ] **Step 4: RefreshTokenService 구현**

```kotlin
// apps/auth/src/main/kotlin/com/bara/auth/application/service/command/RefreshTokenService.kt
package com.bara.auth.application.service.command

import com.bara.auth.application.port.in.command.RefreshTokenUseCase
import com.bara.auth.application.port.out.*
import com.bara.auth.domain.exception.InvalidTokenException
import com.bara.auth.domain.model.TokenPair
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class RefreshTokenService(
    private val refreshTokenIssuer: RefreshTokenIssuer,
    private val refreshTokenStore: RefreshTokenStore,
    private val jwtIssuer: JwtIssuer,
    private val userRepository: UserRepository,
) : RefreshTokenUseCase {

    override fun refresh(refreshToken: String): TokenPair {
        val claims = refreshTokenIssuer.verify(refreshToken)
        val stored = refreshTokenStore.find(claims.userId)
            ?: throw InvalidTokenException()

        if (claims.jti != stored.jti) {
            if (refreshTokenStore.isGraceValid(claims.jti)) {
                WideEvent.message("Grace period 내 이전 Refresh Token 사용 허용")
            } else {
                WideEvent.put("reuse_detected_family", stored.family)
                WideEvent.message("Refresh Token 재사용 감지 — family 무효화")
                refreshTokenStore.delete(claims.userId)
                throw InvalidTokenException()
            }
        }

        val user = userRepository.findById(claims.userId)
            ?: throw InvalidTokenException()

        val newAccessToken = jwtIssuer.issue(user)
        val newRefreshToken = refreshTokenIssuer.issue(claims.userId, stored.family)
        val newClaims = refreshTokenIssuer.verify(newRefreshToken)

        refreshTokenStore.saveGrace(claims.jti)
        refreshTokenStore.save(claims.userId, newClaims.jti, stored.family)

        WideEvent.put("user_id", user.id)
        WideEvent.message("토큰 갱신 성공")

        return TokenPair(accessToken = newAccessToken, refreshToken = newRefreshToken)
    }
}
```

- [ ] **Step 5: 테스트 실행하여 통과 확인**

Run: `./gradlew :apps:auth:test --tests "*.RefreshTokenServiceTest"`
Expected: PASS (5 tests)

- [ ] **Step 6: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/application/service/command/RefreshTokenService.kt \
        apps/auth/src/test/kotlin/com/bara/auth/application/service/command/RefreshTokenServiceTest.kt \
        apps/auth/src/main/kotlin/com/bara/auth/application/port/out/UserRepository.kt
git commit -m "feat(auth): implement RefreshTokenService with rotation and reuse detection"
```

---

### Task 6: LoginWithGoogleService 변경 + 기존 테스트 수정

**Files:**
- Modify: `apps/auth/src/main/kotlin/com/bara/auth/application/service/command/LoginWithGoogleService.kt`
- Modify: `apps/auth/src/test/kotlin/com/bara/auth/application/service/command/LoginWithGoogleServiceTest.kt`

- [ ] **Step 1: LoginWithGoogleService에 RefreshToken 발급 추가**

```kotlin
// apps/auth/src/main/kotlin/com/bara/auth/application/service/command/LoginWithGoogleService.kt
package com.bara.auth.application.service.command

import com.bara.auth.application.port.in.command.LoginWithGoogleUseCase
import com.bara.auth.application.port.out.*
import com.bara.auth.domain.model.TokenPair
import com.bara.auth.domain.model.User
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class LoginWithGoogleService(
    private val googleClient: GoogleOAuthClient,
    private val userRepository: UserRepository,
    private val stateStore: OAuthStateStore,
    private val jwtIssuer: JwtIssuer,
    private val refreshTokenIssuer: RefreshTokenIssuer,
    private val refreshTokenStore: RefreshTokenStore,
) : LoginWithGoogleUseCase {

    override fun buildLoginUrl(): String {
        val state = stateStore.issue()
        return googleClient.buildAuthorizationUrl(state)
    }

    override fun login(code: String, state: String): TokenPair {
        stateStore.consume(state)
        val payload = googleClient.exchangeCodeForIdToken(code)

        val existing = userRepository.findByGoogleId(payload.sub)
        val user = existing ?: userRepository.save(
            User.newUser(googleId = payload.sub, email = payload.email, name = payload.name)
        )

        val accessToken = jwtIssuer.issue(user)
        val family = UUID.randomUUID().toString()
        val refreshToken = refreshTokenIssuer.issue(user.id, family)
        val refreshClaims = refreshTokenIssuer.verify(refreshToken)
        refreshTokenStore.save(user.id, refreshClaims.jti, family)

        WideEvent.put("user_id", user.id)
        WideEvent.put("user_email", user.email)
        WideEvent.put("is_new_user", existing == null)
        WideEvent.message("Google OAuth 로그인 성공")

        return TokenPair(accessToken = accessToken, refreshToken = refreshToken)
    }
}
```

- [ ] **Step 2: LoginWithGoogleServiceTest 수정**

기존 테스트에서 `login()` 반환 타입이 `String`에서 `TokenPair`로 변경됨. 테스트의 mock 설정과 assertion을 업데이트한다. `refreshTokenIssuer`와 `refreshTokenStore` mock을 추가한다.

주요 변경:
- 생성자에 `refreshTokenIssuer`, `refreshTokenStore` 추가
- `every { jwtIssuer.issue(any()) } returns "jwt"` → 유지
- `every { refreshTokenIssuer.issue(any(), any()) } returns "refresh-jwt"` 추가
- `every { refreshTokenIssuer.verify("refresh-jwt") } returns RefreshTokenClaims(...)` 추가
- `every { refreshTokenStore.save(any(), any(), any()) } just Runs` 추가
- `val result = service.login(...)` 반환 타입을 `TokenPair`로 검증
- `assertEquals("jwt", result.accessToken)`

- [ ] **Step 3: 테스트 실행하여 통과 확인**

Run: `./gradlew :apps:auth:test --tests "*.LoginWithGoogleServiceTest"`
Expected: PASS

- [ ] **Step 4: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/application/service/command/LoginWithGoogleService.kt \
        apps/auth/src/test/kotlin/com/bara/auth/application/service/command/LoginWithGoogleServiceTest.kt
git commit -m "feat(auth): add refresh token issuance to Google login flow"
```

---

### Task 7: AuthController 변경 + RefreshController 추가 + 테스트

**Files:**
- Modify: `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthController.kt`
- Create: `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/RefreshController.kt`
- Create: `apps/auth/src/test/kotlin/com/bara/auth/adapter/in/rest/RefreshControllerTest.kt`
- Modify: `apps/auth/src/test/kotlin/com/bara/auth/adapter/in/rest/AuthControllerTest.kt`

- [ ] **Step 1: AuthController 콜백 응답 변경**

`login()` 반환이 `TokenPair`이므로 리다이렉트 URL에 `refreshToken` 파라미터를 추가한다.

```kotlin
// callback 메서드 내부 변경:
val tokenPair = useCase.login(code, state)
WideEvent.put("outcome", "success")
"redirect:${frontendCallbackBase()}?token=${tokenPair.accessToken}&refreshToken=${tokenPair.refreshToken}"
```

- [ ] **Step 2: RefreshController 생성**

```kotlin
// apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/RefreshController.kt
package com.bara.auth.adapter.in.rest

import com.bara.auth.application.port.in.command.RefreshTokenUseCase
import com.bara.common.logging.WideEvent
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class RefreshRequest(val refreshToken: String)

data class RefreshResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
)

@RestController
@RequestMapping("/auth")
class RefreshController(
    private val useCase: RefreshTokenUseCase,
) {
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

- [ ] **Step 3: RefreshControllerTest 작성**

```kotlin
// apps/auth/src/test/kotlin/com/bara/auth/adapter/in/rest/RefreshControllerTest.kt
package com.bara.auth.adapter.in.rest

import com.bara.auth.application.port.in.command.RefreshTokenUseCase
import com.bara.auth.domain.exception.InvalidTokenException
import com.bara.auth.domain.model.TokenPair
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@WebMvcTest(controllers = [RefreshController::class])
@Import(AuthExceptionHandler::class)
class RefreshControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var useCase: RefreshTokenUseCase

    @Test
    fun `유효한 Refresh Token으로 새 토큰 쌍이 반환된다`() {
        every { useCase.refresh("valid-refresh") } returns TokenPair("new-access", "new-refresh")

        mockMvc.post("/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"valid-refresh"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.accessToken") { value("new-access") }
            jsonPath("$.refreshToken") { value("new-refresh") }
            jsonPath("$.expiresIn") { value(3600) }
        }
    }

    @Test
    fun `잘못된 Refresh Token은 401을 반환한다`() {
        every { useCase.refresh("invalid") } throws InvalidTokenException()

        mockMvc.post("/auth/refresh") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"refreshToken":"invalid"}"""
        }.andExpect {
            status { isUnauthorized() }
        }
    }
}
```

- [ ] **Step 4: AuthControllerTest 수정**

콜백 성공 테스트에서 `useCase.login()` 반환이 `TokenPair`이므로 mock과 assertion을 업데이트한다.

```kotlin
// 변경:
every { useCase.login("code-ok", "state-ok") } returns TokenPair("jwt-token", "refresh-token")

// 리다이렉트 URL 검증:
redirectedUrlPattern("**/auth/callback?token=jwt-token&refreshToken=refresh-token")
```

- [ ] **Step 5: AuthExceptionHandler에 InvalidTokenException → 401 매핑 확인**

기존 `AuthExceptionHandler`에서 `InvalidTokenException`이 401로 매핑되는지 확인하고, 없으면 추가한다.

- [ ] **Step 6: 전체 테스트 실행**

Run: `./gradlew :apps:auth:test`
Expected: ALL PASS

- [ ] **Step 7: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthController.kt \
        apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/RefreshController.kt \
        apps/auth/src/test/kotlin/com/bara/auth/adapter/in/rest/RefreshControllerTest.kt \
        apps/auth/src/test/kotlin/com/bara/auth/adapter/in/rest/AuthControllerTest.kt
git commit -m "feat(auth): add POST /auth/refresh endpoint and update login callback"
```

---

### Task 8: UserMongoRepository에 findById 구현 + 테스트

**Files:**
- Modify: `apps/auth/src/main/kotlin/com/bara/auth/adapter/out/persistence/UserMongoRepository.kt`
- Modify: `apps/auth/src/main/kotlin/com/bara/auth/adapter/out/persistence/UserMongoDataRepository.kt`
- Modify: `apps/auth/src/test/kotlin/com/bara/auth/adapter/out/persistence/UserMongoRepositoryTest.kt`

- [ ] **Step 1: UserMongoDataRepository에 findById가 이미 있는지 확인**

Spring Data MongoDB는 `MongoRepository<T, ID>`를 상속하면 `findById(id)`가 자동 제공된다. 기존 인터페이스가 `MongoRepository`를 상속하는지 확인한다. 상속한다면 `UserMongoRepository` 어댑터에 `findById` 위임만 추가하면 된다.

- [ ] **Step 2: UserMongoRepository에 findById 추가**

```kotlin
override fun findById(id: String): User? =
    dataRepository.findById(id).orElse(null)?.toDomain()
```

- [ ] **Step 3: UserMongoRepositoryTest에 findById 테스트 추가**

```kotlin
@Test
fun `findById는 존재하는 사용자를 반환한다`() {
    every { dataRepo.findById("user-1") } returns java.util.Optional.of(UserDocument.fromDomain(user))
    val result = repo.findById("user-1")
    assertEquals(user, result)
}

@Test
fun `findById는 존재하지 않으면 null을 반환한다`() {
    every { dataRepo.findById("unknown") } returns java.util.Optional.empty()
    assertNull(repo.findById("unknown"))
}
```

- [ ] **Step 4: 테스트 실행**

Run: `./gradlew :apps:auth:test --tests "*.UserMongoRepositoryTest"`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/adapter/out/persistence/UserMongoRepository.kt \
        apps/auth/src/main/kotlin/com/bara/auth/application/port/out/UserRepository.kt \
        apps/auth/src/test/kotlin/com/bara/auth/adapter/out/persistence/UserMongoRepositoryTest.kt
git commit -m "feat(auth): add findById to UserRepository for refresh token flow"
```

---

### Task 9: 전체 통합 확인 + 빌드

- [ ] **Step 1: 전체 테스트 실행**

Run: `./gradlew :apps:auth:test`
Expected: ALL PASS

- [ ] **Step 2: 빌드 확인**

Run: `./gradlew :apps:auth:build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 최종 커밋 (필요 시)**

누락된 파일이 있으면 추가 커밋.

---

## Self-Review Checklist

### Spec Coverage
| 스펙 항목 | 태스크 |
|-----------|--------|
| Access Token (기존 유지) | Task 3 (audience 분리 검증) |
| Refresh Token JWT 형식 (family claim) | Task 3 |
| Redis 저장 구조 (refresh:{userId}) | Task 4 |
| Refresh Token Rotation | Task 5 |
| 재사용 감지 (Reuse Detection) | Task 5 |
| Grace Period (30초) | Task 4, 5 |
| POST /auth/refresh 엔드포인트 | Task 7 |
| 로그인 응답 변경 (TokenPair) | Task 6, 7 |
| application.yml 설정 | Task 2 |

### Placeholder Scan
- 모든 step에 코드 포함 ✓
- TBD/TODO 없음 ✓

### Type Consistency
- `TokenPair(accessToken, refreshToken)` 일관 ✓
- `RefreshTokenClaims(userId, jti, family)` 일관 ✓
- `StoredRefreshToken(jti, family)` 일관 ✓

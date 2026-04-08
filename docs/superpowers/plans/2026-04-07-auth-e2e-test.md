# Auth Service E2E Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Auth Service의 전체 비즈니스 흐름을 실제 MongoDB/Redis와 함께 검증하는 E2E 테스트 인프라 구축

**Architecture:** 별도 Gradle source set `e2eTest`에 E2E 테스트를 배치한다. TestContainers로 MongoDB/Redis를 실제로 띄우고, Google OAuth는 hexagonal 포트의 fake 구현체로 대체한다. Happy-path는 시나리오 체인, 에러 케이스는 독립 테스트로 구성한다.

**Tech Stack:** Kotlin, Spring Boot 3.4.4, JUnit 5, TestContainers (GenericContainer), TestRestTemplate, Spring `@DynamicPropertySource`

---

## File Structure

### New Files

| File                                                                              | Responsibility                                                                     |
| --------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| `apps/auth/src/e2eTest/kotlin/com/bara/auth/e2e/support/E2eTestBase.kt`           | 공통 설정: TestContainers, RSA 키 생성, TestRestTemplate, `@DynamicPropertySource` |
| `apps/auth/src/e2eTest/kotlin/com/bara/auth/e2e/support/FakeGoogleOAuthClient.kt` | `GoogleOAuthClient` 포트의 fake 구현체 (E2E 프로파일 전용 Bean)                    |
| `apps/auth/src/e2eTest/kotlin/com/bara/auth/e2e/support/TokenFixture.kt`          | 에러 케이스용 JWT 직접 생성 헬퍼                                                   |
| `apps/auth/src/e2eTest/kotlin/com/bara/auth/e2e/scenario/FullFlowScenarioTest.kt` | Happy-path 시나리오 체인 (12단계)                                                  |
| `apps/auth/src/e2eTest/kotlin/com/bara/auth/e2e/error/ValidateErrorTest.kt`       | 토큰/API Key 검증 에러 테스트                                                      |
| `apps/auth/src/e2eTest/kotlin/com/bara/auth/e2e/error/ProviderErrorTest.kt`       | Provider 관련 에러 테스트                                                          |
| `apps/auth/src/e2eTest/kotlin/com/bara/auth/e2e/error/ApiKeyErrorTest.kt`         | API Key 관련 에러 테스트                                                           |
| `apps/auth/src/e2eTest/resources/application-e2e.yml`                             | E2E 전용 Spring 프로파일                                                           |
| `scripts/smoke-test.sh`                                                           | Gateway-level curl 스모크 테스트                                                   |

### Modified Files

| File                         | Change                                  |
| ---------------------------- | --------------------------------------- |
| `gradle/libs.versions.toml`  | TestContainers 버전 및 라이브러리 추가  |
| `apps/auth/build.gradle.kts` | `e2eTest` source set, task, 의존성 추가 |

---

### Task 1: Gradle 의존성 및 e2eTest source set 설정

**Files:**

- Modify: `gradle/libs.versions.toml`
- Modify: `apps/auth/build.gradle.kts`

- [ ] **Step 1: `libs.versions.toml`에 TestContainers 버전 및 라이브러리 추가**

`gradle/libs.versions.toml`의 `[versions]` 섹션 끝에 추가:

```toml
testcontainers = "1.20.4"
```

`[libraries]` 섹션 끝에 추가:

```toml
testcontainers-core = { module = "org.testcontainers:testcontainers", version.ref = "testcontainers" }
testcontainers-junit-jupiter = { module = "org.testcontainers:junit-jupiter", version.ref = "testcontainers" }
```

- [ ] **Step 2: `build.gradle.kts`에 e2eTest source set 및 의존성 추가**

`apps/auth/build.gradle.kts` 전체를 다음으로 교체:

```kotlin
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    id("bara-spring-boot")
}

dependencies {
    implementation(project(":libs:common"))
    implementation(libs.kotlin.reflect)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.data.mongodb)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.java.jwt)
    implementation(libs.google.api.client)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockk)
    testImplementation(libs.springmockk)
}

// ── e2eTest source set ────────────────────────────────────────────
val e2eTestSourceSet = sourceSets.create("e2eTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[e2eTestSourceSet.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[e2eTestSourceSet.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

dependencies {
    "e2eTestImplementation"(libs.spring.boot.starter.test)
    "e2eTestImplementation"(libs.testcontainers.core)
    "e2eTestImplementation"(libs.testcontainers.junit.jupiter)
}

tasks.register<Test>("e2eTest") {
    description = "Runs E2E tests"
    group = "verification"
    testClassesDirs = e2eTestSourceSet.output.classesDirs
    classpath = e2eTestSourceSet.runtimeClasspath
    useJUnitPlatform()
}
// ──────────────────────────────────────────────────────────────────

tasks.named<BootRun>("bootRun") {
    val envFile = rootProject.file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                val idx = line.indexOf('=')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim().trim('"', '\'')
                    environment(key, value)
                }
            }
    }
}
```

- [ ] **Step 3: 빌드 확인**

Run: `cd /Users/genie/workspace/bara-world && ./gradlew :apps:auth:e2eTest`
Expected: `BUILD SUCCESSFUL` (테스트 0개, 에러 없음)

- [ ] **Step 4: 기존 테스트 영향 없음 확인**

Run: `cd /Users/genie/workspace/bara-world && ./gradlew :apps:auth:test`
Expected: 기존 테스트 전부 PASS

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml apps/auth/build.gradle.kts
git commit -m "test(auth): add e2eTest source set with TestContainers dependencies"
```

---

### Task 2: E2E 테스트 프로파일 및 FakeGoogleOAuthClient

**Files:**

- Create: `apps/auth/src/e2eTest/resources/application-e2e.yml`
- Create: `apps/auth/src/e2eTest/kotlin/com/bara/auth/e2e/support/FakeGoogleOAuthClient.kt`

- [ ] **Step 1: `application-e2e.yml` 생성**

```yaml
bara:
  auth:
    google:
      client-id: e2e-test-client
      client-secret: e2e-test-secret
      redirect-uri: http://localhost:0/api/auth/google/callback
```

`spring.data.mongodb.uri`, `spring.data.redis.*`, JWT 키는 `@DynamicPropertySource`에서 주입하므로 여기에는 Google 프로퍼티만 넣는다.

- [ ] **Step 2: `FakeGoogleOAuthClient.kt` 생성**

```kotlin
package com.bara.auth.e2e.support

import com.bara.auth.application.port.out.GoogleIdTokenPayload
import com.bara.auth.application.port.out.GoogleOAuthClient
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

@Component
@Primary
class FakeGoogleOAuthClient : GoogleOAuthClient {

    var nextPayload: GoogleIdTokenPayload = GoogleIdTokenPayload(
        googleId = "google-e2e-user-001",
        email = "e2e@test.com",
        name = "E2E User",
    )

    override fun buildAuthorizationUrl(state: String): String =
        "http://localhost/fake-google?state=$state"

    override fun exchangeCodeForIdToken(code: String): GoogleIdTokenPayload = nextPayload
}
```

`@Primary`로 선언하여 실제 `GoogleOAuthHttpClient`를 대체한다. `nextPayload`를 테스트에서 변경할 수 있다.

- [ ] **Step 3: Commit**

```bash
git add apps/auth/src/e2eTest/
git commit -m "test(auth): add E2E profile config and FakeGoogleOAuthClient"
```

---

### Task 3: E2eTestBase 공통 설정

**Files:**

- Create: `apps/auth/src/e2eTest/kotlin/com/bara/auth/e2e/support/E2eTestBase.kt`

- [ ] **Step 1: `E2eTestBase.kt` 생성**

```kotlin
package com.bara.auth.e2e.support

import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.security.KeyPairGenerator
import java.util.Base64

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("e2e")
@Testcontainers
abstract class E2eTestBase {

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var mongoTemplate: MongoTemplate

    @BeforeEach
    fun cleanDatabase() {
        mongoTemplate.db.listCollectionNames().forEach { name ->
            mongoTemplate.db.getCollection(name).deleteMany(org.bson.Document())
        }
    }

    companion object {
        @Container
        @JvmStatic
        val mongo: GenericContainer<*> = GenericContainer("mongo:7")
            .withExposedPorts(27017)

        @Container
        @JvmStatic
        val redis: GenericContainer<*> = GenericContainer("redis:7-alpine")
            .withExposedPorts(6379)

        @JvmStatic
        @DynamicPropertySource
        fun containerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.mongodb.uri") {
                "mongodb://${mongo.host}:${mongo.getMappedPort(27017)}/bara-auth-e2e"
            }
            registry.add("spring.data.redis.host") { redis.host }
            registry.add("spring.data.redis.port") { redis.getMappedPort(6379) }

            val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val privPem = toPem("PRIVATE KEY", kp.private.encoded)
            val pubPem = toPem("PUBLIC KEY", kp.public.encoded)
            registry.add("bara.auth.jwt.private-key-base64") {
                Base64.getEncoder().encodeToString(privPem.toByteArray())
            }
            registry.add("bara.auth.jwt.public-key-base64") {
                Base64.getEncoder().encodeToString(pubPem.toByteArray())
            }
        }

        private fun toPem(type: String, bytes: ByteArray): String {
            val b64 = Base64.getEncoder().encodeToString(bytes)
            val lines = b64.chunked(64).joinToString("\n")
            return "-----BEGIN $type-----\n$lines\n-----END $type-----\n"
        }
    }
}
```

- [ ] **Step 2: 빌드 확인**

Run: `cd /Users/genie/workspace/bara-world && ./gradlew :apps:auth:e2eTest`
Expected: `BUILD SUCCESSFUL` (테스트 0개)

- [ ] **Step 3: Commit**

```bash
git add apps/auth/src/e2eTest/kotlin/com/bara/auth/e2e/support/E2eTestBase.kt
git commit -m "test(auth): add E2eTestBase with TestContainers MongoDB/Redis"
```

---

### Task 4: TokenFixture 헬퍼

**Files:**

- Create: `apps/auth/src/e2eTest/kotlin/com/bara/auth/e2e/support/TokenFixture.kt`

- [ ] **Step 1: `TokenFixture.kt` 생성**

```kotlin
package com.bara.auth.e2e.support

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date
import java.util.UUID

object TokenFixture {

    fun expiredJwt(privateKey: RSAPrivateKey, publicKey: RSAPublicKey): String {
        val algorithm = Algorithm.RSA256(publicKey, privateKey)
        val past = Instant.now().minusSeconds(7200)
        return JWT.create()
            .withIssuer("bara-auth")
            .withAudience("bara-world")
            .withSubject("expired-user")
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Date.from(past))
            .withExpiresAt(Date.from(past.plusSeconds(3600)))
            .withClaim("email", "expired@test.com")
            .withClaim("role", "USER")
            .sign(algorithm)
    }

    fun wrongSignatureJwt(): String {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val algorithm = Algorithm.RSA256(kp.public as RSAPublicKey, kp.private as RSAPrivateKey)
        val now = Instant.now()
        return JWT.create()
            .withIssuer("bara-auth")
            .withAudience("bara-world")
            .withSubject("wrong-sig-user")
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(3600)))
            .withClaim("email", "wrong@test.com")
            .withClaim("role", "USER")
            .sign(algorithm)
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add apps/auth/src/e2eTest/kotlin/com/bara/auth/e2e/support/TokenFixture.kt
git commit -m "test(auth): add TokenFixture for E2E error case JWT generation"
```

---

### Task 5: FullFlowScenarioTest (Happy-path 시나리오 체인)

**Files:**

- Create: `apps/auth/src/e2eTest/kotlin/com/bara/auth/e2e/scenario/FullFlowScenarioTest.kt`

- [ ] **Step 1: `FullFlowScenarioTest.kt` 생성**

```kotlin
package com.bara.auth.e2e.scenario

import com.bara.auth.adapter.out.persistence.ProviderDocument
import com.bara.auth.e2e.support.E2eTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FullFlowScenarioTest : E2eTestBase() {

    private lateinit var accessToken: String
    private lateinit var refreshToken: String
    private lateinit var userId: String
    private lateinit var providerId: String
    private lateinit var rawApiKey: String
    private lateinit var apiKeyId: String

    // ── 1. Google 로그인 ──────────────────────────────────────────

    @Test
    @Order(1)
    fun `1 - Google 로그인 후 JWT와 RefreshToken 획득`() {
        // Step 1: login 엔드포인트가 redirect URL을 반환하는지 확인
        val loginResponse = rest.exchange(
            "/api/auth/google/login",
            HttpMethod.GET,
            null,
            Void::class.java,
        )
        assertThat(loginResponse.statusCode).isEqualTo(HttpStatus.FOUND)
        val redirectUrl = loginResponse.headers.location!!.toString()
        assertThat(redirectUrl).contains("fake-google")

        // Step 2: state 파라미터 추출
        val state = redirectUrl.substringAfter("state=")

        // Step 3: callback 호출 (FakeGoogleOAuthClient가 fake payload 반환)
        val callbackResponse = rest.exchange(
            "/api/auth/google/callback?code=fake-code&state=$state",
            HttpMethod.GET,
            null,
            Void::class.java,
        )
        assertThat(callbackResponse.statusCode).isEqualTo(HttpStatus.FOUND)

        // Step 4: redirect URL에서 token, refreshToken 추출
        val callbackRedirect = callbackResponse.headers.location!!.toString()
        assertThat(callbackRedirect).contains("token=")
        assertThat(callbackRedirect).contains("refreshToken=")

        val params = callbackRedirect.substringAfter("?").split("&")
            .associate { it.substringBefore("=") to it.substringAfter("=") }
        accessToken = params["token"]!!
        refreshToken = params["refreshToken"]!!

        assertThat(accessToken).isNotBlank()
        assertThat(refreshToken).isNotBlank()
    }

    // ── 2. JWT validate ──────────────────────────────────────────

    @Test
    @Order(2)
    fun `2 - JWT로 validate 호출 시 X-User-Id 헤더 반환`() {
        val headers = HttpHeaders().apply {
            setBearerAuth(accessToken)
        }
        val response = rest.exchange(
            "/api/auth/validate",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Void::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        userId = response.headers.getFirst("X-User-Id")!!
        assertThat(userId).isNotBlank()
        assertThat(response.headers.getFirst("X-User-Role")).isEqualTo("USER")
        assertThat(response.headers.getFirst("X-Request-Id")).isNotBlank()
    }

    // ── 3. Token refresh ─────────────────────────────────────────

    @Test
    @Order(3)
    fun `3 - RefreshToken으로 새 토큰 쌍 발급`() {
        val body = mapOf("refreshToken" to refreshToken)
        val response = rest.postForEntity("/api/auth/refresh", body, Map::class.java)
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val oldRefreshToken = refreshToken
        accessToken = response.body!!["accessToken"] as String
        refreshToken = response.body!!["refreshToken"] as String
        assertThat(accessToken).isNotBlank()
        assertThat(refreshToken).isNotEqualTo(oldRefreshToken)
    }

    // ── 4. 이전 refresh token 재사용 거부 ────────────────────────

    @Test
    @Order(4)
    fun `4 - 이전 RefreshToken 재사용 시 401`() {
        // Grace period (30초) 후 재사용 → 감지
        // 먼저 한번 더 refresh해서 이전 JTI를 grace 밖으로 밀어냄
        val body1 = mapOf("refreshToken" to refreshToken)
        val response1 = rest.postForEntity("/api/auth/refresh", body1, Map::class.java)
        assertThat(response1.statusCode).isEqualTo(HttpStatus.OK)
        val staleRefreshToken = refreshToken
        accessToken = response1.body!!["accessToken"] as String
        refreshToken = response1.body!!["refreshToken"] as String

        // 2세대 전 토큰으로 재사용 시도 → reuse detected
        val body2 = mapOf("refreshToken" to staleRefreshToken)
        val response2 = rest.postForEntity("/api/auth/refresh", body2, Map::class.java)
        assertThat(response2.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // ── 5. Provider 등록 ─────────────────────────────────────────

    @Test
    @Order(5)
    fun `5 - Provider 등록`() {
        // 4번 테스트에서 family가 무효화되었으므로 다시 로그인
        reLogin()

        val headers = HttpHeaders().apply {
            set("X-User-Id", userId)
            set("Content-Type", "application/json")
        }
        val body = mapOf("name" to "E2E Provider")
        val response = rest.exchange(
            "/api/auth/provider/register",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        providerId = response.body!!["id"] as String
        assertThat(response.body!!["name"]).isEqualTo("E2E Provider")
        assertThat(response.body!!["status"]).isEqualTo("PENDING")
    }

    // ── 6. Provider 조회 ─────────────────────────────────────────

    @Test
    @Order(6)
    fun `6 - Provider 조회 시 PENDING 상태`() {
        val headers = HttpHeaders().apply { set("X-User-Id", userId) }
        val response = rest.exchange(
            "/api/auth/provider",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["status"]).isEqualTo("PENDING")
    }

    // ── 7. API Key 발급 ──────────────────────────────────────────

    @Test
    @Order(7)
    fun `7 - Provider ACTIVE 전환 후 API Key 발급`() {
        // DB에서 직접 Provider status를 ACTIVE로 변경
        val collection = mongoTemplate.db.getCollection("providers")
        collection.updateOne(
            org.bson.Document("_id", providerId),
            org.bson.Document("\$set", org.bson.Document("status", "ACTIVE")),
        )

        val headers = HttpHeaders().apply {
            set("X-User-Id", userId)
            set("Content-Type", "application/json")
        }
        val body = mapOf("name" to "E2E Key")
        val response = rest.exchange(
            "/api/auth/provider/api-key",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        rawApiKey = response.body!!["apiKey"] as String
        apiKeyId = response.body!!["id"] as String
        assertThat(rawApiKey).startsWith("bk_")
    }

    // ── 8. API Key validate ──────────────────────────────────────

    @Test
    @Order(8)
    fun `8 - API Key로 validate 호출 시 X-Provider-Id 반환`() {
        val headers = HttpHeaders().apply { setBearerAuth(rawApiKey) }
        val response = rest.exchange(
            "/api/auth/validate",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Void::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.headers.getFirst("X-Provider-Id")).isEqualTo(providerId)
        assertThat(response.headers.getFirst("X-Request-Id")).isNotBlank()
    }

    // ── 9. API Key 목록 조회 ─────────────────────────────────────

    @Test
    @Order(9)
    fun `9 - API Key 목록 조회 시 1개 반환`() {
        val headers = HttpHeaders().apply { set("X-User-Id", userId) }
        val response = rest.exchange(
            "/api/auth/provider/api-key",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val keys = response.body!!["keys"] as List<*>
        assertThat(keys).hasSize(1)
    }

    // ── 10. API Key 이름 수정 ────────────────────────────────────

    @Test
    @Order(10)
    fun `10 - API Key 이름 수정`() {
        val headers = HttpHeaders().apply {
            set("X-User-Id", userId)
            set("Content-Type", "application/json")
        }
        val body = mapOf("name" to "Renamed Key")
        val response = rest.exchange(
            "/api/auth/provider/api-key/$apiKeyId",
            HttpMethod.PATCH,
            HttpEntity(body, headers),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        assertThat(response.body!!["name"]).isEqualTo("Renamed Key")
    }

    // ── 11. API Key 삭제 ─────────────────────────────────────────

    @Test
    @Order(11)
    fun `11 - API Key 삭제`() {
        val headers = HttpHeaders().apply { set("X-User-Id", userId) }
        val response = rest.exchange(
            "/api/auth/provider/api-key/$apiKeyId",
            HttpMethod.DELETE,
            HttpEntity<Void>(headers),
            Void::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
    }

    // ── 12. 삭제된 API Key validate 실패 ─────────────────────────

    @Test
    @Order(12)
    fun `12 - 삭제된 API Key로 validate 시 401`() {
        val headers = HttpHeaders().apply { setBearerAuth(rawApiKey) }
        val response = rest.exchange(
            "/api/auth/validate",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Void::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // ── Helper ───────────────────────────────────────────────────

    private fun reLogin() {
        val loginResp = rest.exchange(
            "/api/auth/google/login", HttpMethod.GET, null, Void::class.java,
        )
        val state = loginResp.headers.location!!.toString().substringAfter("state=")
        val callbackResp = rest.exchange(
            "/api/auth/google/callback?code=fake-code&state=$state",
            HttpMethod.GET, null, Void::class.java,
        )
        val params = callbackResp.headers.location!!.toString().substringAfter("?").split("&")
            .associate { it.substringBefore("=") to it.substringAfter("=") }
        accessToken = params["token"]!!
        refreshToken = params["refreshToken"]!!
    }
}
```

- [ ] **Step 2: 테스트 실행**

Run: `cd /Users/genie/workspace/bara-world && ./gradlew :apps:auth:e2eTest --tests "com.bara.auth.e2e.scenario.FullFlowScenarioTest" -i`
Expected: 12개 테스트 전부 PASS

- [ ] **Step 3: Commit**

```bash
git add apps/auth/src/e2eTest/kotlin/com/bara/auth/e2e/scenario/FullFlowScenarioTest.kt
git commit -m "test(auth): add FullFlowScenarioTest E2E happy-path chain"
```

---

### Task 6: ValidateErrorTest

**Files:**

- Create: `apps/auth/src/e2eTest/kotlin/com/bara/auth/e2e/error/ValidateErrorTest.kt`

- [ ] **Step 1: `ValidateErrorTest.kt` 생성**

```kotlin
package com.bara.auth.e2e.error

import com.bara.auth.e2e.support.E2eTestBase
import com.bara.auth.e2e.support.TokenFixture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class ValidateErrorTest : E2eTestBase() {

    @Value("\${bara.auth.jwt.private-key-base64}")
    lateinit var privateKeyBase64: String

    @Value("\${bara.auth.jwt.public-key-base64}")
    lateinit var publicKeyBase64: String

    @Test
    fun `Authorization 헤더 없으면 401`() {
        val response = rest.exchange(
            "/api/auth/validate",
            HttpMethod.GET,
            null,
            Void::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `잘못된 서명의 JWT는 401`() {
        val jwt = TokenFixture.wrongSignatureJwt()
        val headers = HttpHeaders().apply { setBearerAuth(jwt) }
        val response = rest.exchange(
            "/api/auth/validate",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Void::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `만료된 JWT는 401`() {
        val (privKey, pubKey) = loadRsaKeys()
        val jwt = TokenFixture.expiredJwt(privKey, pubKey)
        val headers = HttpHeaders().apply { setBearerAuth(jwt) }
        val response = rest.exchange(
            "/api/auth/validate",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Void::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `존재하지 않는 API Key는 401`() {
        val headers = HttpHeaders().apply { setBearerAuth("bk_nonexistent_key_12345678") }
        val response = rest.exchange(
            "/api/auth/validate",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Void::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `SUSPENDED Provider의 API Key는 401`() {
        // DB에 직접 SUSPENDED provider + api key 삽입
        val providerId = "suspended-provider-id"
        val keyHash = sha256("bk_suspended_test_key_123")

        mongoTemplate.db.getCollection("providers").insertOne(
            org.bson.Document(mapOf(
                "_id" to providerId,
                "userId" to "suspended-user",
                "name" to "Suspended Provider",
                "status" to "SUSPENDED",
                "createdAt" to java.time.Instant.now(),
            ))
        )
        mongoTemplate.db.getCollection("api_keys").insertOne(
            org.bson.Document(mapOf(
                "_id" to "suspended-key-id",
                "providerId" to providerId,
                "name" to "Suspended Key",
                "keyHash" to keyHash,
                "keyPrefix" to "bk_suspen",
                "createdAt" to java.time.Instant.now(),
            ))
        )

        val headers = HttpHeaders().apply { setBearerAuth("bk_suspended_test_key_123") }
        val response = rest.exchange(
            "/api/auth/validate",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Void::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    private fun loadRsaKeys(): Pair<RSAPrivateKey, RSAPublicKey> {
        val privPem = String(Base64.getDecoder().decode(privateKeyBase64))
        val pubPem = String(Base64.getDecoder().decode(publicKeyBase64))
        val kf = KeyFactory.getInstance("RSA")
        val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(pemToDer(privPem, "PRIVATE KEY"))) as RSAPrivateKey
        val pubKey = kf.generatePublic(X509EncodedKeySpec(pemToDer(pubPem, "PUBLIC KEY"))) as RSAPublicKey
        return privKey to pubKey
    }

    private fun pemToDer(pem: String, type: String): ByteArray {
        val base64 = pem
            .replace("-----BEGIN $type-----", "")
            .replace("-----END $type-----", "")
            .replace("\\s".toRegex(), "")
        return Base64.getDecoder().decode(base64)
    }

    private fun sha256(input: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
```

- [ ] **Step 2: 테스트 실행**

Run: `cd /Users/genie/workspace/bara-world && ./gradlew :apps:auth:e2eTest --tests "com.bara.auth.e2e.error.ValidateErrorTest" -i`
Expected: 5개 테스트 전부 PASS

- [ ] **Step 3: Commit**

```bash
git add apps/auth/src/e2eTest/kotlin/com/bara/auth/e2e/error/ValidateErrorTest.kt
git commit -m "test(auth): add ValidateErrorTest E2E error cases"
```

---

### Task 7: ProviderErrorTest

**Files:**

- Create: `apps/auth/src/e2eTest/kotlin/com/bara/auth/e2e/error/ProviderErrorTest.kt`

- [ ] **Step 1: `ProviderErrorTest.kt` 생성**

```kotlin
package com.bara.auth.e2e.error

import com.bara.auth.e2e.support.E2eTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

class ProviderErrorTest : E2eTestBase() {

    @Test
    fun `Provider 중복 등록 시 409`() {
        val userId = "duplicate-test-user"
        val headers = HttpHeaders().apply {
            set("X-User-Id", userId)
            set("Content-Type", "application/json")
        }
        val body = mapOf("name" to "Provider 1")

        // 첫 번째 등록: 성공
        val response1 = rest.exchange(
            "/api/auth/provider/register",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java,
        )
        assertThat(response1.statusCode).isEqualTo(HttpStatus.CREATED)

        // 두 번째 등록: 409
        val response2 = rest.exchange(
            "/api/auth/provider/register",
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java,
        )
        assertThat(response2.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response2.body!!["error"]).isEqualTo("provider_already_exists")
    }

    @Test
    fun `미등록 Provider 조회 시 404`() {
        val headers = HttpHeaders().apply { set("X-User-Id", "nonexistent-user") }
        val response = rest.exchange(
            "/api/auth/provider",
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Void::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `PENDING Provider에서 API Key 발급 시 403`() {
        val userId = "pending-test-user"
        val registerHeaders = HttpHeaders().apply {
            set("X-User-Id", userId)
            set("Content-Type", "application/json")
        }

        // Provider 등록 (PENDING 상태)
        rest.exchange(
            "/api/auth/provider/register",
            HttpMethod.POST,
            HttpEntity(mapOf("name" to "Pending Provider"), registerHeaders),
            Map::class.java,
        )

        // PENDING 상태에서 API Key 발급 시도
        val response = rest.exchange(
            "/api/auth/provider/api-key",
            HttpMethod.POST,
            HttpEntity(mapOf("name" to "Key"), registerHeaders),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(response.body!!["error"]).isEqualTo("provider_not_active")
    }
}
```

- [ ] **Step 2: 테스트 실행**

Run: `cd /Users/genie/workspace/bara-world && ./gradlew :apps:auth:e2eTest --tests "com.bara.auth.e2e.error.ProviderErrorTest" -i`
Expected: 3개 테스트 전부 PASS

- [ ] **Step 3: Commit**

```bash
git add apps/auth/src/e2eTest/kotlin/com/bara/auth/e2e/error/ProviderErrorTest.kt
git commit -m "test(auth): add ProviderErrorTest E2E error cases"
```

---

### Task 8: ApiKeyErrorTest

**Files:**

- Create: `apps/auth/src/e2eTest/kotlin/com/bara/auth/e2e/error/ApiKeyErrorTest.kt`

- [ ] **Step 1: `ApiKeyErrorTest.kt` 생성**

```kotlin
package com.bara.auth.e2e.error

import com.bara.auth.e2e.support.E2eTestBase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

class ApiKeyErrorTest : E2eTestBase() {

    private val userId = "apikey-error-test-user"
    private lateinit var headers: HttpHeaders

    @BeforeEach
    fun setUpProvider() {
        headers = HttpHeaders().apply {
            set("X-User-Id", userId)
            set("Content-Type", "application/json")
        }

        // Provider 등록
        rest.exchange(
            "/api/auth/provider/register",
            HttpMethod.POST,
            HttpEntity(mapOf("name" to "ApiKey Error Provider"), headers),
            Map::class.java,
        )

        // DB에서 ACTIVE로 변경
        val providerDoc = mongoTemplate.db.getCollection("providers")
            .find(org.bson.Document("userId", userId)).first()!!
        mongoTemplate.db.getCollection("providers").updateOne(
            org.bson.Document("_id", providerDoc["_id"]),
            org.bson.Document("\$set", org.bson.Document("status", "ACTIVE")),
        )
    }

    @Test
    fun `API Key 5개 초과 발급 시 409`() {
        // 5개 발급
        repeat(5) { i ->
            val response = rest.exchange(
                "/api/auth/provider/api-key",
                HttpMethod.POST,
                HttpEntity(mapOf("name" to "Key-$i"), headers),
                Map::class.java,
            )
            assertThat(response.statusCode).isEqualTo(HttpStatus.CREATED)
        }

        // 6번째 발급 시도
        val response = rest.exchange(
            "/api/auth/provider/api-key",
            HttpMethod.POST,
            HttpEntity(mapOf("name" to "Key-6"), headers),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.body!!["error"]).isEqualTo("api_key_limit_exceeded")
    }

    @Test
    fun `존재하지 않는 keyId 수정 시 404`() {
        val response = rest.exchange(
            "/api/auth/provider/api-key/nonexistent-key-id",
            HttpMethod.PATCH,
            HttpEntity(mapOf("name" to "New Name"), headers),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!["error"]).isEqualTo("api_key_not_found")
    }

    @Test
    fun `존재하지 않는 keyId 삭제 시 404`() {
        val response = rest.exchange(
            "/api/auth/provider/api-key/nonexistent-key-id",
            HttpMethod.DELETE,
            HttpEntity<Void>(headers),
            Map::class.java,
        )
        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(response.body!!["error"]).isEqualTo("api_key_not_found")
    }
}
```

- [ ] **Step 2: 테스트 실행**

Run: `cd /Users/genie/workspace/bara-world && ./gradlew :apps:auth:e2eTest --tests "com.bara.auth.e2e.error.ApiKeyErrorTest" -i`
Expected: 3개 테스트 전부 PASS

- [ ] **Step 3: Commit**

```bash
git add apps/auth/src/e2eTest/kotlin/com/bara/auth/e2e/error/ApiKeyErrorTest.kt
git commit -m "test(auth): add ApiKeyErrorTest E2E error cases"
```

---

### Task 9: Gateway 스모크 테스트 스크립트

**Files:**

- Create: `scripts/smoke-test.sh`

- [ ] **Step 1: `smoke-test.sh` 생성**

```bash
#!/usr/bin/env bash
set -euo pipefail

# Usage: ./scripts/smoke-test.sh [jwt]
# Requires: k3d cluster running (./scripts/k8s.sh create)

JWT="${1:-}"
BASE="http://localhost"
PASS=0
FAIL=0

green() { printf "\033[32m✓ %s\033[0m\n" "$1"; }
red()   { printf "\033[31m✗ %s\033[0m\n" "$1"; }

check() {
    local name="$1" url="$2" expected="$3"
    shift 3
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" "$@" "$url")
    if [ "$code" = "$expected" ]; then
        green "$name (HTTP $code)"
        PASS=$((PASS + 1))
    else
        red "$name (expected $expected, got $code)"
        FAIL=$((FAIL + 1))
    fi
}

check_header() {
    local name="$1" url="$2" header="$3"
    shift 3
    local headers
    headers=$(curl -s -D- -o /dev/null "$@" "$url")
    if echo "$headers" | grep -qi "$header"; then
        green "$name (header $header found)"
        PASS=$((PASS + 1))
    else
        red "$name (header $header not found)"
        FAIL=$((FAIL + 1))
    fi
}

echo "=== Gateway Smoke Test ==="
echo ""

# 1. FE static files
check "FE static files" "$BASE/" "200"

# 2. Public endpoint (Google login redirect)
check "Public: /api/auth/google/login" "$BASE/api/auth/google/login" "302"

# 3. Protected endpoint without auth
check "Protected without auth: /api/auth/provider" "$BASE/api/auth/provider" "401"

# 4. Protected endpoint with valid JWT (if provided)
if [ -n "$JWT" ]; then
    check "Protected with JWT: /api/auth/provider" "$BASE/api/auth/provider" "200" \
        -H "Authorization: Bearer $JWT"
else
    echo "  ⏭  Skipping JWT test (no token provided)"
fi

# 5. CORS preflight
check_header "CORS preflight" "$BASE/api/auth/validate" "Access-Control-Allow-Origin" \
    -X OPTIONS \
    -H "Origin: http://localhost" \
    -H "Access-Control-Request-Method: GET"

# 6. Health check
check "Health check" "$BASE/api/auth/actuator/health" "200"

echo ""
echo "=== Results: $PASS passed, $FAIL failed ==="
exit $FAIL
```

- [ ] **Step 2: 실행 권한 부여**

```bash
chmod +x scripts/smoke-test.sh
```

- [ ] **Step 3: Commit**

```bash
git add scripts/smoke-test.sh
git commit -m "test(auth): add gateway smoke test script"
```

---

### Task 10: 전체 E2E 테스트 실행 및 최종 확인

- [ ] **Step 1: 전체 E2E 테스트 실행**

Run: `cd /Users/genie/workspace/bara-world && ./gradlew :apps:auth:e2eTest -i`
Expected: 23개 테스트 전부 PASS (시나리오 12개 + validate 에러 5개 + provider 에러 3개 + apikey 에러 3개)

- [ ] **Step 2: 기존 단위 테스트 영향 없음 확인**

Run: `cd /Users/genie/workspace/bara-world && ./gradlew :apps:auth:test`
Expected: 기존 테스트 전부 PASS

- [ ] **Step 3: Commit (필요한 경우)**

수정이 있었으면 커밋. 없으면 스킵.

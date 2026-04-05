# Auth Service: Google OAuth + JWT Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Google OAuth 로그인 플로우와 RS256 기반 자체 JWT 발급을 구현하고, React(Vite) 프론트엔드로 end-to-end 동작을 검증한다.

**Architecture:** 헥사고날 아키텍처 — 도메인/애플리케이션은 순수 Kotlin, 외부 의존성(MongoDB, Redis, Google API, JWT 라이브러리)은 port/adapter로 격리. 프론트엔드는 Vite dev proxy를 통해 백엔드와 same-origin으로 통신.

**Tech Stack:** Kotlin 2.1 / Spring Boot 3.4 / MongoDB / Redis / auth0 java-jwt / google-api-client / spring-dotenv / React 19 / Vite 7 / TypeScript 5 / Tailwind CSS 4 / React Router 7 / Vitest

**Spec:** [docs/superpowers/specs/2026-04-05-auth-google-oauth-jwt-design.md](../specs/2026-04-05-auth-google-oauth-jwt-design.md)

---

## 사전 준비

구현 시작 전 다음을 수동으로 준비:

1. **RSA 키쌍 생성** (1회):

   ```bash
   openssl genrsa -out /tmp/jwt-priv.pem 2048
   openssl rsa -in /tmp/jwt-priv.pem -pubout -out /tmp/jwt-pub.pem
   echo "BARA_AUTH_JWT_PRIVATE_KEY=$(base64 < /tmp/jwt-priv.pem | tr -d '\n')"
   echo "BARA_AUTH_JWT_PUBLIC_KEY=$(base64 < /tmp/jwt-pub.pem | tr -d '\n')"
   rm /tmp/jwt-priv.pem /tmp/jwt-pub.pem
   ```

2. **Google Cloud Console OAuth Client 생성**:
   - https://console.cloud.google.com/apis/credentials
   - "Create Credentials" → "OAuth client ID" → "Web application"
   - Authorized redirect URIs: `http://localhost:5173/auth/google/callback`
   - Client ID / Client Secret 확보

3. **`.env` 파일 작성** (Task 1 이후 생성)

4. **인프라 기동**:
   ```bash
   ./scripts/infra.sh up dev
   ```

---

## Task 1: Gradle 의존성 및 .env 인프라

**Files:**

- Modify: `gradle/libs.versions.toml`
- Modify: `apps/auth/build.gradle.kts`
- Create: `.env.example`
- Modify: `.gitignore`

- [ ] **Step 1: Version catalog에 의존성 추가**

Edit `gradle/libs.versions.toml` — 전체 파일 내용:

```toml
[versions]
kotlin = "2.1.20"
spring-boot = "3.4.4"
spring-dependency-management = "1.1.7"
mockk = "1.13.16"
java-jwt = "4.4.0"
google-api-client = "2.7.0"
spring-dotenv = "4.0.0"

[libraries]
# Application dependencies
spring-boot-starter-web = { module = "org.springframework.boot:spring-boot-starter-web" }
spring-boot-starter-data-mongodb = { module = "org.springframework.boot:spring-boot-starter-data-mongodb" }
spring-boot-starter-data-redis = { module = "org.springframework.boot:spring-boot-starter-data-redis" }
spring-boot-starter-actuator = { module = "org.springframework.boot:spring-boot-starter-actuator" }
spring-boot-starter-test = { module = "org.springframework.boot:spring-boot-starter-test" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
java-jwt = { module = "com.auth0:java-jwt", version.ref = "java-jwt" }
google-api-client = { module = "com.google.api-client:google-api-client", version.ref = "google-api-client" }
spring-dotenv = { module = "me.paulschwarz:spring-dotenv", version.ref = "spring-dotenv" }

# Gradle plugin artifacts (for build-logic)
kotlin-gradle-plugin = { module = "org.jetbrains.kotlin:kotlin-gradle-plugin", version.ref = "kotlin" }
kotlin-allopen = { module = "org.jetbrains.kotlin:kotlin-allopen", version.ref = "kotlin" }
spring-boot-gradle-plugin = { module = "org.springframework.boot:spring-boot-gradle-plugin", version.ref = "spring-boot" }
spring-dependency-management-plugin = { module = "io.spring.gradle:dependency-management-plugin", version.ref = "spring-dependency-management" }
```

- [ ] **Step 2: Auth build.gradle.kts 업데이트**

Edit `apps/auth/build.gradle.kts`:

```kotlin
plugins {
    id("bara-spring-boot")
}

dependencies {
    implementation(project(":libs:common"))
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.mongodb)
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.java.jwt)
    implementation(libs.google.api.client)
    implementation(libs.spring.dotenv)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockk)
}
```

- [ ] **Step 3: `.env.example` 작성**

Create `.env.example`:

```bash
# JWT RSA Keys (base64 encoded PEM, generated via openssl)
BARA_AUTH_JWT_PRIVATE_KEY=
BARA_AUTH_JWT_PUBLIC_KEY=

# Google OAuth (Google Cloud Console → APIs & Credentials)
BARA_AUTH_GOOGLE_CLIENT_ID=
BARA_AUTH_GOOGLE_CLIENT_SECRET=
BARA_AUTH_GOOGLE_REDIRECT_URI=http://localhost:5173/auth/google/callback
```

- [ ] **Step 4: `.gitignore` 업데이트**

Edit `.gitignore` — 기존 내용 뒤에 추가:

```
# Local env
.env
*.pem

# Node / Frontend
clients/web/node_modules/
clients/web/dist/
```

- [ ] **Step 5: 빌드 검증**

Run: `./gradlew :apps:auth:build`
Expected: `BUILD SUCCESSFUL` (기존 HealthCheckTest 통과)

- [ ] **Step 6: `.env` 파일 작성 (로컬, 커밋 안 함)**

프로젝트 루트에 `.env` 생성 후 사전 준비에서 얻은 값들을 채운다. 이 파일은 gitignored.

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml apps/auth/build.gradle.kts .env.example .gitignore
git commit -m "chore(auth): Google OAuth + JWT 의존성 및 .env 인프라 추가"
```

---

## Task 2: JWT Properties & ConfigurationProperties 바인딩

**Files:**

- Create: `apps/auth/src/main/kotlin/com/bara/auth/config/JwtProperties.kt`
- Modify: `apps/auth/src/main/kotlin/com/bara/auth/BaraAuthApplication.kt`
- Modify: `apps/auth/src/main/resources/application.yml`

- [ ] **Step 1: JwtProperties 작성**

Create `apps/auth/src/main/kotlin/com/bara/auth/config/JwtProperties.kt`:

```kotlin
package com.bara.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.util.Base64

@ConfigurationProperties(prefix = "bara.auth.jwt")
data class JwtProperties(
    val issuer: String,
    val audience: String,
    val expirySeconds: Long,
    val privateKeyBase64: String,
    val publicKeyBase64: String,
) {
    fun privateKeyPem(): String = String(Base64.getDecoder().decode(privateKeyBase64))
    fun publicKeyPem(): String = String(Base64.getDecoder().decode(publicKeyBase64))
}
```

- [ ] **Step 2: ConfigurationPropertiesScan 활성화**

Edit `apps/auth/src/main/kotlin/com/bara/auth/BaraAuthApplication.kt`:

```kotlin
package com.bara.auth

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
class BaraAuthApplication

fun main(args: Array<String>) {
    runApplication<BaraAuthApplication>(*args)
}
```

- [ ] **Step 3: application.yml에 JWT 설정 추가**

Edit `apps/auth/src/main/resources/application.yml` — 전체 내용:

```yaml
spring:
  application:
    name: bara-auth
  data:
    mongodb:
      uri: mongodb://localhost:27017/bara-auth
    redis:
      host: localhost
      port: 6379

server:
  port: 8081

management:
  endpoints:
    web:
      exposure:
        include: health
  endpoint:
    health:
      probes:
        enabled: true
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true

bara:
  auth:
    jwt:
      issuer: bara-auth
      audience: bara-world
      expiry-seconds: 3600
      private-key-base64: ${BARA_AUTH_JWT_PRIVATE_KEY}
      public-key-base64: ${BARA_AUTH_JWT_PUBLIC_KEY}
    google:
      client-id: ${BARA_AUTH_GOOGLE_CLIENT_ID}
      client-secret: ${BARA_AUTH_GOOGLE_CLIENT_SECRET}
      redirect-uri: ${BARA_AUTH_GOOGLE_REDIRECT_URI}
```

- [ ] **Step 4: 빌드 검증 (아직 Google/Redis Property 클래스 없음 — 환경변수만 확인)**

Run: `./gradlew :apps:auth:compileKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/config/JwtProperties.kt \
        apps/auth/src/main/kotlin/com/bara/auth/BaraAuthApplication.kt \
        apps/auth/src/main/resources/application.yml
git commit -m "feat(auth): JwtProperties와 application.yml 설정 바인딩 추가"
```

---

## Task 3: User 도메인 모델

**Files:**

- Create: `apps/auth/src/main/kotlin/com/bara/auth/domain/model/User.kt`

- [ ] **Step 1: User 도메인 모델 작성**

Create `apps/auth/src/main/kotlin/com/bara/auth/domain/model/User.kt`:

```kotlin
package com.bara.auth.domain.model

import java.time.Instant
import java.util.UUID

data class User(
    val id: String,
    val googleId: String,
    val email: String,
    val name: String,
    val role: Role,
    val createdAt: Instant,
) {
    enum class Role { USER, ADMIN }

    companion object {
        fun newUser(
            googleId: String,
            email: String,
            name: String,
            now: Instant = Instant.now(),
        ): User = User(
            id = UUID.randomUUID().toString(),
            googleId = googleId,
            email = email,
            name = name,
            role = Role.USER,
            createdAt = now,
        )
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :apps:auth:compileKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/domain/model/User.kt
git commit -m "feat(auth): User 도메인 모델과 newUser 팩토리 추가"
```

---

## Task 4: JWT Port 정의

**Files:**

- Create: `apps/auth/src/main/kotlin/com/bara/auth/application/port/out/JwtIssuer.kt`
- Create: `apps/auth/src/main/kotlin/com/bara/auth/application/port/out/JwtVerifier.kt`
- Create: `apps/auth/src/main/kotlin/com/bara/auth/domain/exception/InvalidTokenException.kt`

- [ ] **Step 1: InvalidTokenException 작성**

Create `apps/auth/src/main/kotlin/com/bara/auth/domain/exception/InvalidTokenException.kt`:

```kotlin
package com.bara.auth.domain.exception

class InvalidTokenException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
```

- [ ] **Step 2: JwtIssuer 포트 작성**

Create `apps/auth/src/main/kotlin/com/bara/auth/application/port/out/JwtIssuer.kt`:

```kotlin
package com.bara.auth.application.port.out

import com.bara.auth.domain.model.User

interface JwtIssuer {
    fun issue(user: User): String
}
```

- [ ] **Step 3: JwtVerifier 포트 + JwtClaims 작성**

Create `apps/auth/src/main/kotlin/com/bara/auth/application/port/out/JwtVerifier.kt`:

```kotlin
package com.bara.auth.application.port.out

interface JwtVerifier {
    /** 검증 실패 시 com.bara.auth.domain.exception.InvalidTokenException throw */
    fun verify(token: String): JwtClaims
}

data class JwtClaims(
    val userId: String,
    val email: String,
    val role: String,
)
```

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew :apps:auth:compileKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/application/port/out/JwtIssuer.kt \
        apps/auth/src/main/kotlin/com/bara/auth/application/port/out/JwtVerifier.kt \
        apps/auth/src/main/kotlin/com/bara/auth/domain/exception/InvalidTokenException.kt
git commit -m "feat(auth): JwtIssuer/JwtVerifier 포트와 InvalidTokenException 추가"
```

---

## Task 5: RS256 JWT Adapter (TDD)

**Files:**

- Create: `apps/auth/src/test/kotlin/com/bara/auth/adapter/out/external/Rs256JwtAdapterTest.kt`
- Create: `apps/auth/src/main/kotlin/com/bara/auth/adapter/out/external/Rs256JwtAdapter.kt`

- [ ] **Step 1: 실패 테스트 작성 (라운드트립)**

Create `apps/auth/src/test/kotlin/com/bara/auth/adapter/out/external/Rs256JwtAdapterTest.kt`:

```kotlin
package com.bara.auth.adapter.out.external

import com.bara.auth.config.JwtProperties
import com.bara.auth.domain.exception.InvalidTokenException
import com.bara.auth.domain.model.User
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.time.Instant
import java.util.Base64

class Rs256JwtAdapterTest {

    private lateinit var adapter: Rs256JwtAdapter

    private val user = User(
        id = "user-123",
        googleId = "google-abc",
        email = "test@example.com",
        name = "Test User",
        role = User.Role.USER,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @BeforeEach
    fun setUp() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val privPem = toPem("PRIVATE KEY", kp.private.encoded)
        val pubPem = toPem("PUBLIC KEY", kp.public.encoded)
        val props = JwtProperties(
            issuer = "bara-auth",
            audience = "bara-world",
            expirySeconds = 3600,
            privateKeyBase64 = Base64.getEncoder().encodeToString(privPem.toByteArray()),
            publicKeyBase64 = Base64.getEncoder().encodeToString(pubPem.toByteArray()),
        )
        adapter = Rs256JwtAdapter(props)
    }

    @Test
    fun `발급한 토큰을 검증하면 클레임이 반환된다`() {
        val token = adapter.issue(user)
        val claims = adapter.verify(token)
        assertEquals("user-123", claims.userId)
        assertEquals("test@example.com", claims.email)
        assertEquals("USER", claims.role)
    }

    @Test
    fun `잘못된 서명의 토큰은 InvalidTokenException을 던진다`() {
        val token = adapter.issue(user)
        val tampered = token.dropLast(4) + "AAAA"
        assertThrows(InvalidTokenException::class.java) { adapter.verify(tampered) }
    }

    @Test
    fun `다른 발급자로 서명된 토큰은 InvalidTokenException을 던진다`() {
        val otherKp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val otherProps = JwtProperties(
            issuer = "bara-auth",
            audience = "bara-world",
            expirySeconds = 3600,
            privateKeyBase64 = Base64.getEncoder().encodeToString(
                toPem("PRIVATE KEY", otherKp.private.encoded).toByteArray()
            ),
            publicKeyBase64 = Base64.getEncoder().encodeToString(
                toPem("PUBLIC KEY", otherKp.public.encoded).toByteArray()
            ),
        )
        val otherAdapter = Rs256JwtAdapter(otherProps)
        val tokenFromOther = otherAdapter.issue(user)
        assertThrows(InvalidTokenException::class.java) { adapter.verify(tokenFromOther) }
    }

    @Test
    fun `임의의 문자열은 InvalidTokenException을 던진다`() {
        assertThrows(InvalidTokenException::class.java) { adapter.verify("not-a-jwt") }
    }

    private fun toPem(type: String, bytes: ByteArray): String {
        val b64 = Base64.getEncoder().encodeToString(bytes)
        val lines = b64.chunked(64).joinToString("\n")
        return "-----BEGIN $type-----\n$lines\n-----END $type-----\n"
    }
}
```

- [ ] **Step 2: 테스트가 컴파일 실패하는지 확인**

Run: `./gradlew :apps:auth:test --tests Rs256JwtAdapterTest`
Expected: FAIL — `Unresolved reference: Rs256JwtAdapter`

- [ ] **Step 3: Rs256JwtAdapter 구현**

Create `apps/auth/src/main/kotlin/com/bara/auth/adapter/out/external/Rs256JwtAdapter.kt`:

```kotlin
package com.bara.auth.adapter.out.external

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.bara.auth.application.port.out.JwtClaims
import com.bara.auth.application.port.out.JwtIssuer
import com.bara.auth.application.port.out.JwtVerifier
import com.bara.auth.config.JwtProperties
import com.bara.auth.domain.exception.InvalidTokenException
import com.bara.auth.domain.model.User
import org.springframework.stereotype.Component
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID

@Component
class Rs256JwtAdapter(
    private val props: JwtProperties,
) : JwtIssuer, JwtVerifier {

    private val privateKey: RSAPrivateKey = loadPrivateKey(props.privateKeyPem())
    private val publicKey: RSAPublicKey = loadPublicKey(props.publicKeyPem())
    private val algorithm: Algorithm = Algorithm.RSA256(publicKey, privateKey)

    private val verifier = JWT.require(algorithm)
        .withIssuer(props.issuer)
        .withAudience(props.audience)
        .build()

    override fun issue(user: User): String {
        val now = Instant.now()
        return JWT.create()
            .withIssuer(props.issuer)
            .withAudience(props.audience)
            .withSubject(user.id)
            .withJWTId(UUID.randomUUID().toString())
            .withIssuedAt(Date.from(now))
            .withExpiresAt(Date.from(now.plusSeconds(props.expirySeconds)))
            .withClaim("email", user.email)
            .withClaim("role", user.role.name)
            .sign(algorithm)
    }

    override fun verify(token: String): JwtClaims {
        try {
            val decoded = verifier.verify(token)
            return JwtClaims(
                userId = decoded.subject,
                email = decoded.getClaim("email").asString(),
                role = decoded.getClaim("role").asString(),
            )
        } catch (e: JWTVerificationException) {
            throw InvalidTokenException("JWT 검증 실패: ${e.message}", e)
        }
    }

    private fun loadPrivateKey(pem: String): RSAPrivateKey {
        val der = pemToDer(pem, "PRIVATE KEY")
        val spec = PKCS8EncodedKeySpec(der)
        return KeyFactory.getInstance("RSA").generatePrivate(spec) as RSAPrivateKey
    }

    private fun loadPublicKey(pem: String): RSAPublicKey {
        val der = pemToDer(pem, "PUBLIC KEY")
        val spec = X509EncodedKeySpec(der)
        return KeyFactory.getInstance("RSA").generatePublic(spec) as RSAPublicKey
    }

    private fun pemToDer(pem: String, type: String): ByteArray {
        val header = "-----BEGIN $type-----"
        val footer = "-----END $type-----"
        val base64 = pem
            .replace(header, "")
            .replace(footer, "")
            .replace("\\s".toRegex(), "")
        return Base64.getDecoder().decode(base64)
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :apps:auth:test --tests Rs256JwtAdapterTest`
Expected: PASS — 4개 테스트 모두 성공

- [ ] **Step 5: Commit**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/adapter/out/external/Rs256JwtAdapter.kt \
        apps/auth/src/test/kotlin/com/bara/auth/adapter/out/external/Rs256JwtAdapterTest.kt
git commit -m "feat(auth): Rs256JwtAdapter 구현 및 발급/검증 테스트"
```

---

## Task 6: UserRepository Port

**Files:**

- Create: `apps/auth/src/main/kotlin/com/bara/auth/application/port/out/UserRepository.kt`

- [ ] **Step 1: UserRepository 포트 작성**

Create `apps/auth/src/main/kotlin/com/bara/auth/application/port/out/UserRepository.kt`:

```kotlin
package com.bara.auth.application.port.out

import com.bara.auth.domain.model.User

interface UserRepository {
    fun findByGoogleId(googleId: String): User?
    fun save(user: User): User
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :apps:auth:compileKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/application/port/out/UserRepository.kt
git commit -m "feat(auth): UserRepository 포트 추가"
```

---

## Task 7: User MongoDB Document & Adapter (TDD)

**Files:**

- Create: `apps/auth/src/main/kotlin/com/bara/auth/adapter/out/persistence/UserDocument.kt`
- Create: `apps/auth/src/main/kotlin/com/bara/auth/adapter/out/persistence/UserMongoDataRepository.kt`
- Create: `apps/auth/src/test/kotlin/com/bara/auth/adapter/out/persistence/UserMongoRepositoryTest.kt`
- Create: `apps/auth/src/main/kotlin/com/bara/auth/adapter/out/persistence/UserMongoRepository.kt`

- [ ] **Step 1: UserDocument 작성**

Create `apps/auth/src/main/kotlin/com/bara/auth/adapter/out/persistence/UserDocument.kt`:

```kotlin
package com.bara.auth.adapter.out.persistence

import com.bara.auth.domain.model.User
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "users")
data class UserDocument(
    @Id val id: String,
    @Indexed(unique = true) val googleId: String,
    val email: String,
    val name: String,
    val role: String,
    val createdAt: Instant,
) {
    fun toDomain(): User = User(
        id = id,
        googleId = googleId,
        email = email,
        name = name,
        role = User.Role.valueOf(role),
        createdAt = createdAt,
    )

    companion object {
        fun fromDomain(user: User): UserDocument = UserDocument(
            id = user.id,
            googleId = user.googleId,
            email = user.email,
            name = user.name,
            role = user.role.name,
            createdAt = user.createdAt,
        )
    }
}
```

- [ ] **Step 2: UserMongoDataRepository 작성**

Create `apps/auth/src/main/kotlin/com/bara/auth/adapter/out/persistence/UserMongoDataRepository.kt`:

```kotlin
package com.bara.auth.adapter.out.persistence

import org.springframework.data.mongodb.repository.MongoRepository

interface UserMongoDataRepository : MongoRepository<UserDocument, String> {
    fun findByGoogleId(googleId: String): UserDocument?
}
```

- [ ] **Step 3: UserMongoRepositoryTest 작성 (실패 상태)**

Create `apps/auth/src/test/kotlin/com/bara/auth/adapter/out/persistence/UserMongoRepositoryTest.kt`:

```kotlin
package com.bara.auth.adapter.out.persistence

import com.bara.auth.domain.model.User
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant

class UserMongoRepositoryTest {

    private val dataRepo = mockk<UserMongoDataRepository>()
    private val repo = UserMongoRepository(dataRepo)

    private val user = User(
        id = "user-1",
        googleId = "g-1",
        email = "a@b.com",
        name = "Alice",
        role = User.Role.USER,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun `findByGoogleId는 존재하면 도메인 User를 반환한다`() {
        every { dataRepo.findByGoogleId("g-1") } returns UserDocument.fromDomain(user)
        val result = repo.findByGoogleId("g-1")
        assertEquals(user, result)
    }

    @Test
    fun `findByGoogleId는 없으면 null을 반환한다`() {
        every { dataRepo.findByGoogleId("missing") } returns null
        assertNull(repo.findByGoogleId("missing"))
    }

    @Test
    fun `save는 Document로 변환 후 저장하고 도메인으로 되돌려준다`() {
        val captured = slot<UserDocument>()
        every { dataRepo.save(capture(captured)) } answers { captured.captured }
        val result = repo.save(user)
        assertEquals(user, result)
        assertEquals("user-1", captured.captured.id)
        assertEquals("USER", captured.captured.role)
        verify(exactly = 1) { dataRepo.save(any()) }
    }
}
```

- [ ] **Step 4: 테스트 실패 확인**

Run: `./gradlew :apps:auth:test --tests UserMongoRepositoryTest`
Expected: FAIL — `Unresolved reference: UserMongoRepository`

- [ ] **Step 5: UserMongoRepository 구현**

Create `apps/auth/src/main/kotlin/com/bara/auth/adapter/out/persistence/UserMongoRepository.kt`:

```kotlin
package com.bara.auth.adapter.out.persistence

import com.bara.auth.application.port.out.UserRepository
import com.bara.auth.domain.model.User
import org.springframework.stereotype.Repository

@Repository
class UserMongoRepository(
    private val dataRepository: UserMongoDataRepository,
) : UserRepository {
    override fun findByGoogleId(googleId: String): User? =
        dataRepository.findByGoogleId(googleId)?.toDomain()

    override fun save(user: User): User =
        dataRepository.save(UserDocument.fromDomain(user)).toDomain()
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew :apps:auth:test --tests UserMongoRepositoryTest`
Expected: PASS — 3개 테스트 모두 성공

- [ ] **Step 7: Commit**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/adapter/out/persistence/ \
        apps/auth/src/test/kotlin/com/bara/auth/adapter/out/persistence/
git commit -m "feat(auth): UserMongoRepository 어댑터 구현 및 도메인 변환 테스트"
```

---

## Task 8: OAuth State Store Port & Redis Adapter (TDD)

**Files:**

- Create: `apps/auth/src/main/kotlin/com/bara/auth/application/port/out/OAuthStateStore.kt`
- Create: `apps/auth/src/main/kotlin/com/bara/auth/domain/exception/InvalidOAuthStateException.kt`
- Create: `apps/auth/src/test/kotlin/com/bara/auth/adapter/out/external/RedisOAuthStateStoreTest.kt`
- Create: `apps/auth/src/main/kotlin/com/bara/auth/adapter/out/external/RedisOAuthStateStore.kt`

- [ ] **Step 1: Exception 작성**

Create `apps/auth/src/main/kotlin/com/bara/auth/domain/exception/InvalidOAuthStateException.kt`:

```kotlin
package com.bara.auth.domain.exception

class InvalidOAuthStateException(message: String = "OAuth state 검증 실패") : RuntimeException(message)
```

- [ ] **Step 2: OAuthStateStore 포트 작성**

Create `apps/auth/src/main/kotlin/com/bara/auth/application/port/out/OAuthStateStore.kt`:

```kotlin
package com.bara.auth.application.port.out

interface OAuthStateStore {
    /** 랜덤 state 생성 + 저장소에 저장(TTL). 생성된 state 반환. */
    fun issue(): String

    /** state 존재 확인 + 즉시 삭제. 없으면 InvalidOAuthStateException. */
    fun consume(state: String)
}
```

- [ ] **Step 3: 테스트 작성**

Create `apps/auth/src/test/kotlin/com/bara/auth/adapter/out/external/RedisOAuthStateStoreTest.kt`:

```kotlin
package com.bara.auth.adapter.out.external

import com.bara.auth.domain.exception.InvalidOAuthStateException
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

class RedisOAuthStateStoreTest {

    private val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
    private val template = mockk<StringRedisTemplate> {
        every { opsForValue() } returns valueOps
    }
    private val store = RedisOAuthStateStore(template)

    @Test
    fun `issue는 새로운 state를 생성하고 Redis에 TTL과 함께 저장한다`() {
        val state = store.issue()
        assertTrue(state.isNotBlank())
        verify { valueOps.set("oauth:state:$state", "", Duration.ofSeconds(300)) }
    }

    @Test
    fun `consume은 존재하는 state를 조회 후 삭제한다`() {
        every { template.hasKey("oauth:state:abc") } returns true
        every { template.delete("oauth:state:abc") } returns true
        store.consume("abc")
        verify { template.delete("oauth:state:abc") }
    }

    @Test
    fun `consume은 존재하지 않는 state에 대해 InvalidOAuthStateException을 던진다`() {
        every { template.hasKey("oauth:state:missing") } returns false
        assertThrows(InvalidOAuthStateException::class.java) { store.consume("missing") }
    }
}
```

- [ ] **Step 4: 실패 확인**

Run: `./gradlew :apps:auth:test --tests RedisOAuthStateStoreTest`
Expected: FAIL — `Unresolved reference: RedisOAuthStateStore`

- [ ] **Step 5: RedisOAuthStateStore 구현**

Create `apps/auth/src/main/kotlin/com/bara/auth/adapter/out/external/RedisOAuthStateStore.kt`:

```kotlin
package com.bara.auth.adapter.out.external

import com.bara.auth.application.port.out.OAuthStateStore
import com.bara.auth.domain.exception.InvalidOAuthStateException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

@Component
class RedisOAuthStateStore(
    private val redis: StringRedisTemplate,
) : OAuthStateStore {

    override fun issue(): String {
        val state = UUID.randomUUID().toString()
        redis.opsForValue().set(keyOf(state), "", TTL)
        return state
    }

    override fun consume(state: String) {
        val key = keyOf(state)
        if (redis.hasKey(key) != true) {
            throw InvalidOAuthStateException()
        }
        redis.delete(key)
    }

    private fun keyOf(state: String): String = "oauth:state:$state"

    companion object {
        private val TTL: Duration = Duration.ofSeconds(300)
    }
}
```

- [ ] **Step 6: 테스트 통과**

Run: `./gradlew :apps:auth:test --tests RedisOAuthStateStoreTest`
Expected: PASS — 3개 테스트 모두 성공

- [ ] **Step 7: Commit**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/application/port/out/OAuthStateStore.kt \
        apps/auth/src/main/kotlin/com/bara/auth/domain/exception/InvalidOAuthStateException.kt \
        apps/auth/src/main/kotlin/com/bara/auth/adapter/out/external/RedisOAuthStateStore.kt \
        apps/auth/src/test/kotlin/com/bara/auth/adapter/out/external/RedisOAuthStateStoreTest.kt
git commit -m "feat(auth): OAuthStateStore 포트와 Redis 어댑터 구현"
```

---

## Task 9: Google OAuth Properties & Port

**Files:**

- Create: `apps/auth/src/main/kotlin/com/bara/auth/config/GoogleOAuthProperties.kt`
- Create: `apps/auth/src/main/kotlin/com/bara/auth/application/port/out/GoogleOAuthClient.kt`
- Create: `apps/auth/src/main/kotlin/com/bara/auth/domain/exception/GoogleExchangeFailedException.kt`
- Create: `apps/auth/src/main/kotlin/com/bara/auth/domain/exception/InvalidIdTokenException.kt`

- [ ] **Step 1: GoogleOAuthProperties 작성**

Create `apps/auth/src/main/kotlin/com/bara/auth/config/GoogleOAuthProperties.kt`:

```kotlin
package com.bara.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "bara.auth.google")
data class GoogleOAuthProperties(
    val clientId: String,
    val clientSecret: String,
    val redirectUri: String,
)
```

- [ ] **Step 2: Exceptions 작성**

Create `apps/auth/src/main/kotlin/com/bara/auth/domain/exception/GoogleExchangeFailedException.kt`:

```kotlin
package com.bara.auth.domain.exception

class GoogleExchangeFailedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
```

Create `apps/auth/src/main/kotlin/com/bara/auth/domain/exception/InvalidIdTokenException.kt`:

```kotlin
package com.bara.auth.domain.exception

class InvalidIdTokenException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
```

- [ ] **Step 3: GoogleOAuthClient 포트 작성**

Create `apps/auth/src/main/kotlin/com/bara/auth/application/port/out/GoogleOAuthClient.kt`:

```kotlin
package com.bara.auth.application.port.out

interface GoogleOAuthClient {
    /** Google 인증 URL 조립 (scope: openid email profile). */
    fun buildAuthorizationUrl(state: String): String

    /**
     * Authorization code를 ID token으로 교환 후 서명 검증한 페이로드 반환.
     *
     * @throws com.bara.auth.domain.exception.GoogleExchangeFailedException code 교환 실패
     * @throws com.bara.auth.domain.exception.InvalidIdTokenException ID token 서명/클레임 검증 실패
     */
    fun exchangeCodeForIdToken(code: String): GoogleIdTokenPayload
}

data class GoogleIdTokenPayload(
    val googleId: String,
    val email: String,
    val name: String,
)
```

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew :apps:auth:compileKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/config/GoogleOAuthProperties.kt \
        apps/auth/src/main/kotlin/com/bara/auth/application/port/out/GoogleOAuthClient.kt \
        apps/auth/src/main/kotlin/com/bara/auth/domain/exception/GoogleExchangeFailedException.kt \
        apps/auth/src/main/kotlin/com/bara/auth/domain/exception/InvalidIdTokenException.kt
git commit -m "feat(auth): GoogleOAuthClient 포트와 GoogleOAuthProperties 추가"
```

---

## Task 10: Google OAuth HTTP Client Adapter

**Files:**

- Create: `apps/auth/src/main/kotlin/com/bara/auth/adapter/out/external/GoogleOAuthHttpClient.kt`

> **주의**: Google API 라이브러리의 `GoogleAuthorizationCodeTokenRequest`와 `GoogleIdTokenVerifier`는 concrete class여서 단위 테스트가 복잡하다. 설계 문서에 따라 이 어댑터는 **단위 테스트를 생략**하고, 서비스 레이어에서 포트를 mock해 검증한다. 실제 동작 검증은 Task 15 (수동 E2E)에서 수행.

- [ ] **Step 1: GoogleOAuthHttpClient 구현**

Create `apps/auth/src/main/kotlin/com/bara/auth/adapter/out/external/GoogleOAuthHttpClient.kt`:

```kotlin
package com.bara.auth.adapter.out.external

import com.bara.auth.application.port.out.GoogleIdTokenPayload
import com.bara.auth.application.port.out.GoogleOAuthClient
import com.bara.auth.config.GoogleOAuthProperties
import com.bara.auth.domain.exception.GoogleExchangeFailedException
import com.bara.auth.domain.exception.InvalidIdTokenException
import com.google.api.client.auth.oauth2.TokenResponseException
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeTokenRequest
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Component
class GoogleOAuthHttpClient(
    private val props: GoogleOAuthProperties,
) : GoogleOAuthClient {

    private val transport = NetHttpTransport()
    private val jsonFactory = GsonFactory.getDefaultInstance()

    private val verifier: GoogleIdTokenVerifier = GoogleIdTokenVerifier.Builder(transport, jsonFactory)
        .setAudience(listOf(props.clientId))
        .build()

    override fun buildAuthorizationUrl(state: String): String {
        val params = mapOf(
            "client_id" to props.clientId,
            "redirect_uri" to props.redirectUri,
            "response_type" to "code",
            "scope" to "openid email profile",
            "state" to state,
            "access_type" to "online",
            "prompt" to "select_account",
        )
        val query = params.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
        }
        return "https://accounts.google.com/o/oauth2/v2/auth?$query"
    }

    override fun exchangeCodeForIdToken(code: String): GoogleIdTokenPayload {
        val tokenResponse = try {
            GoogleAuthorizationCodeTokenRequest(
                transport,
                jsonFactory,
                props.clientId,
                props.clientSecret,
                code,
                props.redirectUri,
            ).execute()
        } catch (e: TokenResponseException) {
            throw GoogleExchangeFailedException("Google code 교환 실패: ${e.details?.error ?: e.message}", e)
        } catch (e: Exception) {
            throw GoogleExchangeFailedException("Google code 교환 중 예외: ${e.message}", e)
        }

        val idTokenString: String = tokenResponse.idToken
            ?: throw InvalidIdTokenException("Google 응답에 id_token이 없음")

        val idToken: GoogleIdToken = try {
            verifier.verify(idTokenString)
                ?: throw InvalidIdTokenException("ID token 검증 실패 (서명 또는 audience 불일치)")
        } catch (e: Exception) {
            if (e is InvalidIdTokenException) throw e
            throw InvalidIdTokenException("ID token 검증 중 예외: ${e.message}", e)
        }

        val payload = idToken.payload
        return GoogleIdTokenPayload(
            googleId = payload.subject,
            email = payload.email ?: throw InvalidIdTokenException("ID token에 email 클레임 없음"),
            name = (payload["name"] as? String) ?: payload.email,
        )
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :apps:auth:compileKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/adapter/out/external/GoogleOAuthHttpClient.kt
git commit -m "feat(auth): GoogleOAuthHttpClient 어댑터 구현"
```

---

## Task 11: LoginWithGoogle UseCase & Service (TDD)

**Files:**

- Create: `apps/auth/src/main/kotlin/com/bara/auth/application/port/in/command/LoginWithGoogleUseCase.kt`
- Create: `apps/auth/src/test/kotlin/com/bara/auth/application/service/command/LoginWithGoogleServiceTest.kt`
- Create: `apps/auth/src/main/kotlin/com/bara/auth/application/service/command/LoginWithGoogleService.kt`

- [ ] **Step 1: UseCase 포트 작성**

Create `apps/auth/src/main/kotlin/com/bara/auth/application/port/in/command/LoginWithGoogleUseCase.kt`:

```kotlin
package com.bara.auth.application.port.`in`.command

interface LoginWithGoogleUseCase {
    /** state와 code를 검증하고 자체 JWT를 반환한다. */
    fun login(code: String, state: String): String

    /** Google 인증 URL을 조립한다 (state 생성 포함). */
    fun buildLoginUrl(): String
}
```

- [ ] **Step 2: 테스트 작성**

Create `apps/auth/src/test/kotlin/com/bara/auth/application/service/command/LoginWithGoogleServiceTest.kt`:

```kotlin
package com.bara.auth.application.service.command

import com.bara.auth.application.port.out.GoogleIdTokenPayload
import com.bara.auth.application.port.out.GoogleOAuthClient
import com.bara.auth.application.port.out.JwtIssuer
import com.bara.auth.application.port.out.OAuthStateStore
import com.bara.auth.application.port.out.UserRepository
import com.bara.auth.domain.exception.InvalidOAuthStateException
import com.bara.auth.domain.model.User
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant

class LoginWithGoogleServiceTest {

    private val googleClient = mockk<GoogleOAuthClient>()
    private val userRepo = mockk<UserRepository>()
    private val stateStore = mockk<OAuthStateStore>()
    private val jwtIssuer = mockk<JwtIssuer>()

    private val service = LoginWithGoogleService(googleClient, userRepo, stateStore, jwtIssuer)

    private val payload = GoogleIdTokenPayload(
        googleId = "google-123",
        email = "test@example.com",
        name = "Test User",
    )

    private val existingUser = User(
        id = "user-1",
        googleId = "google-123",
        email = "test@example.com",
        name = "Test User",
        role = User.Role.USER,
        createdAt = Instant.parse("2025-01-01T00:00:00Z"),
    )

    @Test
    fun `기존 사용자는 저장 없이 JWT 발급`() {
        every { stateStore.consume("state-ok") } just Runs
        every { googleClient.exchangeCodeForIdToken("code-ok") } returns payload
        every { userRepo.findByGoogleId("google-123") } returns existingUser
        every { jwtIssuer.issue(existingUser) } returns "jwt.token.existing"

        val token = service.login(code = "code-ok", state = "state-ok")

        assertEquals("jwt.token.existing", token)
        verify(exactly = 0) { userRepo.save(any()) }
    }

    @Test
    fun `신규 사용자는 저장 후 JWT 발급`() {
        val savedSlot = slot<User>()
        every { stateStore.consume("state-ok") } just Runs
        every { googleClient.exchangeCodeForIdToken("code-ok") } returns payload
        every { userRepo.findByGoogleId("google-123") } returns null
        every { userRepo.save(capture(savedSlot)) } answers { savedSlot.captured }
        every { jwtIssuer.issue(any()) } returns "jwt.token.new"

        val token = service.login(code = "code-ok", state = "state-ok")

        assertEquals("jwt.token.new", token)
        assertEquals("google-123", savedSlot.captured.googleId)
        assertEquals("test@example.com", savedSlot.captured.email)
        assertEquals(User.Role.USER, savedSlot.captured.role)
        verify(exactly = 1) { userRepo.save(any()) }
    }

    @Test
    fun `state 검증 실패 시 예외가 전파된다`() {
        every { stateStore.consume("bad") } throws InvalidOAuthStateException()

        assertThrows(InvalidOAuthStateException::class.java) {
            service.login(code = "code", state = "bad")
        }

        verify(exactly = 0) { googleClient.exchangeCodeForIdToken(any()) }
    }

    @Test
    fun `buildLoginUrl은 state를 발급해 Google URL을 조립한다`() {
        every { stateStore.issue() } returns "generated-state"
        every { googleClient.buildAuthorizationUrl("generated-state") } returns "https://google/auth?state=generated-state"

        val url = service.buildLoginUrl()

        assertEquals("https://google/auth?state=generated-state", url)
    }
}
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `./gradlew :apps:auth:test --tests LoginWithGoogleServiceTest`
Expected: FAIL — `Unresolved reference: LoginWithGoogleService`

- [ ] **Step 4: Service 구현**

Create `apps/auth/src/main/kotlin/com/bara/auth/application/service/command/LoginWithGoogleService.kt`:

```kotlin
package com.bara.auth.application.service.command

import com.bara.auth.application.port.`in`.command.LoginWithGoogleUseCase
import com.bara.auth.application.port.out.GoogleOAuthClient
import com.bara.auth.application.port.out.JwtIssuer
import com.bara.auth.application.port.out.OAuthStateStore
import com.bara.auth.application.port.out.UserRepository
import com.bara.auth.domain.model.User
import org.springframework.stereotype.Service

@Service
class LoginWithGoogleService(
    private val googleClient: GoogleOAuthClient,
    private val userRepository: UserRepository,
    private val stateStore: OAuthStateStore,
    private val jwtIssuer: JwtIssuer,
) : LoginWithGoogleUseCase {

    override fun buildLoginUrl(): String {
        val state = stateStore.issue()
        return googleClient.buildAuthorizationUrl(state)
    }

    override fun login(code: String, state: String): String {
        stateStore.consume(state)
        val payload = googleClient.exchangeCodeForIdToken(code)
        val user = userRepository.findByGoogleId(payload.googleId)
            ?: userRepository.save(
                User.newUser(
                    googleId = payload.googleId,
                    email = payload.email,
                    name = payload.name,
                )
            )
        return jwtIssuer.issue(user)
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :apps:auth:test --tests LoginWithGoogleServiceTest`
Expected: PASS — 4개 테스트 모두 성공

- [ ] **Step 6: Commit**

```bash
git add apps/auth/src/main/kotlin/com/bara/auth/application/port/in/command/LoginWithGoogleUseCase.kt \
        apps/auth/src/main/kotlin/com/bara/auth/application/service/command/LoginWithGoogleService.kt \
        apps/auth/src/test/kotlin/com/bara/auth/application/service/command/LoginWithGoogleServiceTest.kt
git commit -m "feat(auth): LoginWithGoogleService와 UseCase 포트 구현"
```

---

## Task 12: AuthController & Exception Handler (TDD)

**Files:**

- Create: `apps/auth/src/test/kotlin/com/bara/auth/adapter/in/rest/AuthControllerTest.kt`
- Create: `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthController.kt`
- Create: `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthExceptionHandler.kt`
- Modify: `apps/auth/src/main/kotlin/com/bara/auth/config/GoogleOAuthProperties.kt` (no change needed, reference only)

- [ ] **Step 1: Controller 테스트 작성**

Create `apps/auth/src/test/kotlin/com/bara/auth/adapter/in/rest/AuthControllerTest.kt`:

```kotlin
package com.bara.auth.adapter.`in`.rest

import com.bara.auth.application.port.`in`.command.LoginWithGoogleUseCase
import com.bara.auth.config.GoogleOAuthProperties
import com.bara.auth.domain.exception.GoogleExchangeFailedException
import com.bara.auth.domain.exception.InvalidIdTokenException
import com.bara.auth.domain.exception.InvalidOAuthStateException
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@WebMvcTest(controllers = [AuthController::class])
@Import(AuthExceptionHandler::class, GoogleOAuthProperties::class)
@TestPropertySource(properties = [
    "bara.auth.google.client-id=test-client",
    "bara.auth.google.client-secret=test-secret",
    "bara.auth.google.redirect-uri=http://localhost:5173/auth/google/callback",
])
class AuthControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var useCase: LoginWithGoogleUseCase

    @Test
    fun `GET auth google login 은 Google 로그인 URL로 302 리다이렉트`() {
        every { useCase.buildLoginUrl() } returns "https://accounts.google.com/oauth?state=xyz"

        mockMvc.get("/auth/google/login")
            .andExpect {
                status { isFound() }
                header { string("Location", "https://accounts.google.com/oauth?state=xyz") }
            }
    }

    @Test
    fun `GET auth google callback 성공 시 FE로 토큰과 함께 302`() {
        every { useCase.login("code-1", "state-1") } returns "jwt.token.xxx"

        mockMvc.get("/auth/google/callback") {
            param("code", "code-1")
            param("state", "state-1")
        }.andExpect {
            status { isFound() }
            header { string("Location", "http://localhost:5173/auth/callback?token=jwt.token.xxx") }
        }
    }

    @Test
    fun `callback에서 state 불일치 시 invalid_state 에러로 302`() {
        every { useCase.login(any(), any()) } throws InvalidOAuthStateException()

        mockMvc.get("/auth/google/callback") {
            param("code", "c")
            param("state", "bad")
        }.andExpect {
            status { isFound() }
            header { string("Location", "http://localhost:5173/auth/callback?error=invalid_state") }
        }
    }

    @Test
    fun `callback에서 code 교환 실패 시 google_exchange_failed 에러로 302`() {
        every { useCase.login(any(), any()) } throws GoogleExchangeFailedException("x")

        mockMvc.get("/auth/google/callback") {
            param("code", "c")
            param("state", "s")
        }.andExpect {
            status { isFound() }
            header { string("Location", "http://localhost:5173/auth/callback?error=google_exchange_failed") }
        }
    }

    @Test
    fun `callback에서 id_token 검증 실패 시 invalid_id_token 에러로 302`() {
        every { useCase.login(any(), any()) } throws InvalidIdTokenException("x")

        mockMvc.get("/auth/google/callback") {
            param("code", "c")
            param("state", "s")
        }.andExpect {
            status { isFound() }
            header { string("Location", "http://localhost:5173/auth/callback?error=invalid_id_token") }
        }
    }
}
```

- [ ] **Step 2: `com.ninjasquad:springmockk` 테스트 의존성 추가**

Edit `gradle/libs.versions.toml` — `[versions]`와 `[libraries]`에 추가:

```toml
[versions]
# ... 기존 ...
springmockk = "4.0.2"

[libraries]
# ... 기존 ...
springmockk = { module = "com.ninja-squad:springmockk", version.ref = "springmockk" }
```

Edit `apps/auth/build.gradle.kts` — `dependencies` 블록에 추가:

```kotlin
    testImplementation(libs.springmockk)
```

- [ ] **Step 3: 테스트 컴파일 실패 확인**

Run: `./gradlew :apps:auth:test --tests AuthControllerTest`
Expected: FAIL — `Unresolved reference: AuthController`

- [ ] **Step 4: AuthExceptionHandler 구현**

Create `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthExceptionHandler.kt`:

```kotlin
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
```

- [ ] **Step 5: AuthController 구현**

Create `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthController.kt`:

```kotlin
package com.bara.auth.adapter.`in`.rest

import com.bara.auth.application.port.`in`.command.LoginWithGoogleUseCase
import com.bara.auth.config.GoogleOAuthProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI

@RestController
@RequestMapping("/auth/google")
class AuthController(
    private val useCase: LoginWithGoogleUseCase,
    private val googleProps: GoogleOAuthProperties,
) {

    @GetMapping("/login")
    fun login(): ResponseEntity<Void> {
        val url = useCase.buildLoginUrl()
        return redirect(url)
    }

    @GetMapping("/callback")
    fun callback(
        @RequestParam code: String,
        @RequestParam state: String,
    ): ResponseEntity<Void> {
        val jwt = useCase.login(code = code, state = state)
        return redirect("${frontendCallbackBase()}?token=$jwt")
    }

    private fun redirect(url: String): ResponseEntity<Void> {
        val headers = HttpHeaders().apply { location = URI.create(url) }
        return ResponseEntity(headers, HttpStatus.FOUND)
    }

    private fun frontendCallbackBase(): String =
        googleProps.redirectUri.replace("/auth/google/callback", "/auth/callback")
}
```

- [ ] **Step 6: 테스트 통과 확인**

Run: `./gradlew :apps:auth:test --tests AuthControllerTest`
Expected: PASS — 5개 테스트 모두 성공

- [ ] **Step 7: Commit**

```bash
git add gradle/libs.versions.toml \
        apps/auth/build.gradle.kts \
        apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/ \
        apps/auth/src/test/kotlin/com/bara/auth/adapter/in/rest/
git commit -m "feat(auth): AuthController와 예외 핸들러 구현"
```

---

## Task 13: 전체 빌드 & 스모크 기동 검증

**Files:**

- (코드 변경 없음)

- [ ] **Step 1: 전체 빌드**

Run: `./gradlew :apps:auth:build`
Expected: `BUILD SUCCESSFUL` — 모든 테스트 통과

- [ ] **Step 2: 인프라 기동 확인**

Run: `./scripts/infra.sh up dev`
Expected: mongodb/redis/kafka 3개 컨테이너 running

- [ ] **Step 3: `.env` 파일 존재 확인**

Run: `test -f .env && echo OK || echo MISSING`
Expected: `OK` (사전 준비에서 생성됨)

- [ ] **Step 4: Auth Service 실행**

Run: `./gradlew :apps:auth:bootRun`
Expected: 로그에 `Started BaraAuthApplication`, Tomcat 8081, MongoDB/Redis 연결 성공

- [ ] **Step 5: 헬스체크 확인**

다른 터미널에서:

```bash
curl -s http://localhost:8081/actuator/health
```

Expected: `{"status":"UP"}`

- [ ] **Step 6: 로그인 URL 리다이렉트 확인**

```bash
curl -s -o /dev/null -w "%{http_code} %{redirect_url}\n" http://localhost:8081/auth/google/login
```

Expected: `302 https://accounts.google.com/o/oauth2/v2/auth?...state=...`

- [ ] **Step 7: Auth Service 중지**

실행 중이던 bootRun을 Ctrl+C로 종료.

- [ ] **Step 8: (커밋 없음, 검증만)**

이 Task는 파일 변경이 없으므로 커밋하지 않음.

---

## Task 14: Frontend 스캐폴딩 (Vite + React + TypeScript + Tailwind)

**Files:**

- Create: `clients/web/package.json`
- Create: `clients/web/pnpm-lock.yaml` (pnpm install 결과)
- Create: `clients/web/tsconfig.json`
- Create: `clients/web/tsconfig.node.json`
- Create: `clients/web/vite.config.ts`
- Create: `clients/web/tailwind.config.ts`
- Create: `clients/web/postcss.config.js`
- Create: `clients/web/index.html`
- Create: `clients/web/src/main.tsx`
- Create: `clients/web/src/App.tsx`
- Create: `clients/web/src/index.css`
- Create: `clients/web/.gitignore`

> **참고**: `pnpm create vite` 템플릿을 쓰지 않고 수동으로 파일을 작성한다 (템플릿이 만드는 불필요한 데모 파일을 배제).

- [ ] **Step 1: 디렉토리 생성 및 package.json 작성**

Run: `mkdir -p clients/web/src/pages clients/web/src/lib clients/web/src/__tests__`

Create `clients/web/package.json`:

```json
{
  "name": "@bara/web",
  "private": true,
  "version": "0.0.1",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc -b && vite build",
    "preview": "vite preview",
    "test": "vitest run",
    "test:watch": "vitest"
  },
  "dependencies": {
    "react": "^19.0.0",
    "react-dom": "^19.0.0",
    "react-router-dom": "^7.1.1"
  },
  "devDependencies": {
    "@types/react": "^19.0.0",
    "@types/react-dom": "^19.0.0",
    "@vitejs/plugin-react": "^4.3.4",
    "@tailwindcss/postcss": "^4.0.0",
    "@tailwindcss/vite": "^4.0.0",
    "tailwindcss": "^4.0.0",
    "postcss": "^8.4.49",
    "autoprefixer": "^10.4.20",
    "typescript": "^5.7.2",
    "vite": "^7.0.0",
    "vitest": "^2.1.8",
    "jsdom": "^25.0.1",
    "@testing-library/jest-dom": "^6.6.3"
  }
}
```

> 버전은 명시된 값이 최소 요건. `pnpm install` 시 lock 파일이 실제 해석된 버전을 고정.

- [ ] **Step 2: pnpm install**

Run: `cd clients/web && pnpm install`
Expected: `node_modules/` 생성, `pnpm-lock.yaml` 생성

- [ ] **Step 3: tsconfig.json 작성**

Create `clients/web/tsconfig.json`:

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "useDefineForClassFields": true,
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "noFallthroughCasesInSwitch": true,
    "types": ["vitest/globals", "@testing-library/jest-dom"]
  },
  "include": ["src"],
  "references": [{ "path": "./tsconfig.node.json" }]
}
```

Create `clients/web/tsconfig.node.json`:

```json
{
  "compilerOptions": {
    "composite": true,
    "skipLibCheck": true,
    "module": "ESNext",
    "moduleResolution": "bundler",
    "allowSyntheticDefaultImports": true,
    "strict": true
  },
  "include": ["vite.config.ts"]
}
```

- [ ] **Step 4: vite.config.ts 작성**

Create `clients/web/vite.config.ts`:

```ts
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      '/auth/google': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/__tests__/setup.ts'],
  },
});
```

- [ ] **Step 5: Tailwind v4 설정**

Create `clients/web/src/index.css`:

```css
@import 'tailwindcss';
```

Tailwind v4는 별도 `tailwind.config.ts`가 필수가 아니지만 호환을 위해 빈 파일 생성.

Create `clients/web/tailwind.config.ts`:

```ts
import type { Config } from 'tailwindcss';

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
} satisfies Config;
```

Create `clients/web/postcss.config.js`:

```js
export default {
  plugins: {
    '@tailwindcss/postcss': {},
    autoprefixer: {},
  },
};
```

- [ ] **Step 6: index.html 작성**

Create `clients/web/index.html`:

```html
<!doctype html>
<html lang="ko">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>Bara World</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

- [ ] **Step 7: main.tsx와 App.tsx 작성 (skeleton)**

Create `clients/web/src/main.tsx`:

```tsx
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import './index.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
);
```

Create `clients/web/src/App.tsx`:

```tsx
export default function App() {
  return (
    <div className="min-h-screen flex items-center justify-center">
      <p className="text-xl">Bara World — scaffold ready</p>
    </div>
  );
}
```

- [ ] **Step 8: Vitest setup 파일**

Create `clients/web/src/__tests__/setup.ts`:

```ts
import '@testing-library/jest-dom';
```

- [ ] **Step 9: FE .gitignore**

Create `clients/web/.gitignore`:

```
node_modules/
dist/
.vite/
coverage/
```

- [ ] **Step 10: 타입체크와 빌드 검증**

Run: `cd clients/web && pnpm build`
Expected: `dist/` 생성, 타입 에러 없음

- [ ] **Step 11: dev 서버 기동 확인**

Run: `cd clients/web && pnpm dev`
Expected: `Local: http://localhost:5173/` 출력. 브라우저에서 "Bara World — scaffold ready" 확인.
중지: Ctrl+C

- [ ] **Step 12: Vitest 실행 확인 (테스트 0개지만 동작 확인)**

Run: `cd clients/web && pnpm test`
Expected: `No test files found` 또는 빈 성공 — 에러 없이 종료

- [ ] **Step 13: Commit**

```bash
git add clients/web/package.json \
        clients/web/pnpm-lock.yaml \
        clients/web/tsconfig.json \
        clients/web/tsconfig.node.json \
        clients/web/vite.config.ts \
        clients/web/tailwind.config.ts \
        clients/web/postcss.config.js \
        clients/web/index.html \
        clients/web/src/ \
        clients/web/.gitignore
git commit -m "feat(auth): clients/web Vite+React+TS+Tailwind 스캐폴딩"
```

> **Scope 주의**: `clients/web`은 `auth` 서비스의 일부로 간주 (이번 iteration에서 Auth 로그인 테스트용). 커밋 scope는 `auth`.

---

## Task 15: Frontend lib 유틸 (auth.ts, jwt.ts) TDD

**Files:**

- Create: `clients/web/src/__tests__/auth.test.ts`
- Create: `clients/web/src/lib/auth.ts`
- Create: `clients/web/src/__tests__/jwt.test.ts`
- Create: `clients/web/src/lib/jwt.ts`

- [ ] **Step 1: auth.test.ts 작성**

Create `clients/web/src/__tests__/auth.test.ts`:

```ts
import { afterEach, describe, expect, it } from 'vitest';
import { clearToken, getToken, saveToken } from '../lib/auth';

describe('auth storage', () => {
  afterEach(() => {
    localStorage.clear();
  });

  it('saveToken 후 getToken으로 같은 값이 반환된다', () => {
    saveToken('abc.def.ghi');
    expect(getToken()).toBe('abc.def.ghi');
  });

  it('토큰이 없으면 getToken은 null을 반환한다', () => {
    expect(getToken()).toBeNull();
  });

  it('clearToken 후 getToken은 null', () => {
    saveToken('t');
    clearToken();
    expect(getToken()).toBeNull();
  });
});
```

- [ ] **Step 2: 실패 확인**

Run: `cd clients/web && pnpm test`
Expected: FAIL — `Cannot find module '../lib/auth'`

- [ ] **Step 3: auth.ts 구현**

Create `clients/web/src/lib/auth.ts`:

```ts
const KEY = 'bara.auth.token';

export function saveToken(token: string): void {
  localStorage.setItem(KEY, token);
}

export function getToken(): string | null {
  return localStorage.getItem(KEY);
}

export function clearToken(): void {
  localStorage.removeItem(KEY);
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd clients/web && pnpm test`
Expected: PASS — 3개

- [ ] **Step 5: jwt.test.ts 작성**

Create `clients/web/src/__tests__/jwt.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { decodeJwtPayload } from '../lib/jwt';

describe('decodeJwtPayload', () => {
  it('base64url 인코딩된 payload를 디코드한다', () => {
    // header.payload.signature — payload는 {"sub":"user-1","email":"a@b.com","role":"USER"}
    const payload = btoa(JSON.stringify({ sub: 'user-1', email: 'a@b.com', role: 'USER' }))
      .replace(/=/g, '')
      .replace(/\+/g, '-')
      .replace(/\//g, '_');
    const token = `header.${payload}.sig`;

    const result = decodeJwtPayload(token);

    expect(result.sub).toBe('user-1');
    expect(result.email).toBe('a@b.com');
    expect(result.role).toBe('USER');
  });

  it('형식이 잘못된 토큰은 예외를 던진다', () => {
    expect(() => decodeJwtPayload('not-a-jwt')).toThrow();
  });
});
```

- [ ] **Step 6: 실패 확인**

Run: `cd clients/web && pnpm test`
Expected: FAIL — `Cannot find module '../lib/jwt'`

- [ ] **Step 7: jwt.ts 구현**

Create `clients/web/src/lib/jwt.ts`:

```ts
export function decodeJwtPayload(token: string): Record<string, unknown> {
  const parts = token.split('.');
  if (parts.length !== 3) {
    throw new Error('Invalid JWT format');
  }
  const base64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
  const padded = base64 + '='.repeat((4 - (base64.length % 4)) % 4);
  const json = atob(padded);
  return JSON.parse(json) as Record<string, unknown>;
}
```

- [ ] **Step 8: 테스트 통과**

Run: `cd clients/web && pnpm test`
Expected: PASS — 5개 (auth 3개 + jwt 2개)

- [ ] **Step 9: Commit**

```bash
git add clients/web/src/lib/ clients/web/src/__tests__/auth.test.ts clients/web/src/__tests__/jwt.test.ts
git commit -m "feat(auth): FE lib/auth lib/jwt 유틸과 테스트"
```

---

## Task 16: Frontend 페이지 (LoginPage, CallbackPage, MePage)

**Files:**

- Create: `clients/web/src/pages/LoginPage.tsx`
- Create: `clients/web/src/pages/CallbackPage.tsx`
- Create: `clients/web/src/pages/MePage.tsx`
- Modify: `clients/web/src/App.tsx`

- [ ] **Step 1: LoginPage 작성**

Create `clients/web/src/pages/LoginPage.tsx`:

```tsx
export default function LoginPage() {
  return (
    <div className="min-h-screen flex flex-col items-center justify-center gap-6">
      <h1 className="text-3xl font-bold">Bara World</h1>
      <a
        href="/auth/google/login"
        className="px-6 py-3 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition"
      >
        Login with Google
      </a>
    </div>
  );
}
```

- [ ] **Step 2: CallbackPage 작성**

Create `clients/web/src/pages/CallbackPage.tsx`:

```tsx
import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { saveToken } from '../lib/auth';

export default function CallbackPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const token = params.get('token');
    const err = params.get('error');
    if (err) {
      setError(err);
      return;
    }
    if (token) {
      saveToken(token);
      navigate('/me', { replace: true });
    }
  }, [params, navigate]);

  if (error) {
    return (
      <div className="min-h-screen flex flex-col items-center justify-center gap-4">
        <h1 className="text-2xl font-bold text-red-600">로그인 실패</h1>
        <p className="text-gray-700">{error}</p>
        <a href="/" className="text-blue-600 underline">
          돌아가기
        </a>
      </div>
    );
  }

  return (
    <div className="min-h-screen flex items-center justify-center">
      <p>로그인 처리 중...</p>
    </div>
  );
}
```

- [ ] **Step 3: MePage 작성**

Create `clients/web/src/pages/MePage.tsx`:

```tsx
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { clearToken, getToken } from '../lib/auth';
import { decodeJwtPayload } from '../lib/jwt';

export default function MePage() {
  const navigate = useNavigate();
  const [token, setToken] = useState<string | null>(null);
  const [payload, setPayload] = useState<Record<string, unknown> | null>(null);

  useEffect(() => {
    const t = getToken();
    if (!t) {
      navigate('/', { replace: true });
      return;
    }
    setToken(t);
    try {
      setPayload(decodeJwtPayload(t));
    } catch {
      setPayload(null);
    }
  }, [navigate]);

  function logout() {
    clearToken();
    navigate('/', { replace: true });
  }

  function copyToken() {
    if (token) navigator.clipboard.writeText(token);
  }

  if (!token) return null;

  return (
    <div className="min-h-screen p-8 max-w-2xl mx-auto">
      <h1 className="text-3xl font-bold mb-6">My Info</h1>
      {payload && (
        <div className="bg-gray-100 rounded-lg p-4 mb-6">
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2">
            <dt className="font-semibold">Email</dt>
            <dd>{String(payload.email ?? '-')}</dd>
            <dt className="font-semibold">Role</dt>
            <dd>{String(payload.role ?? '-')}</dd>
            <dt className="font-semibold">User ID</dt>
            <dd className="font-mono text-sm">{String(payload.sub ?? '-')}</dd>
            <dt className="font-semibold">Expires</dt>
            <dd>{payload.exp ? new Date(Number(payload.exp) * 1000).toLocaleString() : '-'}</dd>
          </dl>
        </div>
      )}
      <div className="mb-6">
        <label className="block font-semibold mb-2">Raw JWT</label>
        <textarea
          readOnly
          value={token}
          className="w-full h-32 p-2 border rounded font-mono text-xs"
        />
        <button
          onClick={copyToken}
          className="mt-2 px-4 py-2 bg-gray-200 rounded hover:bg-gray-300"
        >
          Copy token
        </button>
      </div>
      <button onClick={logout} className="px-4 py-2 bg-red-600 text-white rounded hover:bg-red-700">
        Logout
      </button>
    </div>
  );
}
```

- [ ] **Step 4: App.tsx 라우팅 수정**

Edit `clients/web/src/App.tsx` — 전체 내용 교체:

```tsx
import { Route, Routes } from 'react-router-dom';
import LoginPage from './pages/LoginPage';
import CallbackPage from './pages/CallbackPage';
import MePage from './pages/MePage';

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<LoginPage />} />
      <Route path="/auth/callback" element={<CallbackPage />} />
      <Route path="/me" element={<MePage />} />
    </Routes>
  );
}
```

- [ ] **Step 5: 빌드 & 테스트**

Run: `cd clients/web && pnpm build && pnpm test`
Expected: 빌드 성공, 기존 5개 테스트 통과 (lib 유틸만 테스트 대상)

- [ ] **Step 6: Commit**

```bash
git add clients/web/src/pages/ clients/web/src/App.tsx
git commit -m "feat(auth): FE LoginPage/CallbackPage/MePage와 라우팅 구성"
```

---

## Task 17: End-to-End 수동 검증

**Files:**

- (코드 변경 없음)

- [ ] **Step 1: 인프라 & 백엔드 & 프론트엔드 기동**

터미널 3개 필요:

```bash
# 터미널 1
./scripts/infra.sh up dev

# 터미널 2
./gradlew :apps:auth:bootRun

# 터미널 3
cd clients/web && pnpm dev
```

- [ ] **Step 2: 브라우저 접속**

브라우저에서 `http://localhost:5173/` 접속
Expected: "Login with Google" 버튼이 있는 페이지

- [ ] **Step 3: Google 로그인 수행**

"Login with Google" 클릭 → Google 로그인 → 동의 → `/me` 페이지 도달
Expected:

- URL이 `http://localhost:5173/me`
- Email, Role(`USER`), User ID(UUID), Expires 표시
- Raw JWT textarea에 JWT 전체

- [ ] **Step 4: JWT 구조 확인**

"Copy token" 클릭 → https://jwt.io 에 붙여넣기
Expected:

- Header: `{"alg":"RS256","typ":"JWT"}`
- Payload: `iss: bara-auth`, `aud: bara-world`, `sub`, `email`, `role: USER`, `jti`, `iat`, `exp`
- Signature: "Invalid Signature" (공개키 없으므로 당연함)

- [ ] **Step 5: MongoDB에 사용자 저장 확인**

```bash
docker exec -it $(docker ps -qf "name=mongo") mongosh bara-auth --eval "db.users.find().pretty()"
```

Expected: 1개 row, `googleId`, `email`, `name`, `role: "USER"`, `createdAt`

- [ ] **Step 6: 재로그인 시 중복 저장 없음 확인**

브라우저에서 Logout → 다시 Login with Google → 같은 계정 선택
Expected: `/me` 도달, MongoDB에 여전히 1개 row (새 row 생성 안 됨)

- [ ] **Step 7: 에러 케이스 — 잘못된 state**

브라우저 주소창에 직접 입력:

```
http://localhost:5173/auth/google/callback?code=fake&state=invalid
```

Expected: `/auth/callback?error=invalid_state` 도달, "로그인 실패: invalid_state" 표시

- [ ] **Step 8: 로그아웃 확인**

`/me`에서 Logout 클릭
Expected: `/`로 이동, localStorage의 `bara.auth.token` 삭제 (DevTools로 확인)

- [ ] **Step 9: 정리**

3개 터미널 모두 Ctrl+C로 중지. 인프라는 유지해도 됨.

- [ ] **Step 10: 커밋 없음**

수동 검증만 수행. 파일 변경 없음.

---

## Self-Review 체크리스트 (구현 완료 후)

모든 Task 완료 후 다음을 확인:

- [ ] 단위 테스트 전부 통과: `./gradlew :apps:auth:test` & `cd clients/web && pnpm test`
- [ ] 전체 빌드 성공: `./gradlew :apps:auth:build` & `cd clients/web && pnpm build`
- [ ] `.env` 파일 git에 커밋되지 않았음: `git ls-files | grep -E '^\.env$' | wc -l` → 0
- [ ] `.env.example`은 커밋됨: `git ls-files | grep .env.example`
- [ ] PEM 파일이 커밋되지 않았음: `git ls-files | grep -E '\.pem$' | wc -l` → 0
- [ ] 헥사고날 의존 방향 준수: `grep -r "com.bara.auth.adapter" apps/auth/src/main/kotlin/com/bara/auth/domain/` → 결과 없음
- [ ] 헥사고날 의존 방향 준수: `grep -r "com.bara.auth.adapter" apps/auth/src/main/kotlin/com/bara/auth/application/` → 결과 없음

---

## 파일 변경 요약

### 생성되는 파일 (Backend)

```
apps/auth/src/main/kotlin/com/bara/auth/
├── domain/
│   ├── model/User.kt
│   └── exception/
│       ├── InvalidTokenException.kt
│       ├── InvalidOAuthStateException.kt
│       ├── GoogleExchangeFailedException.kt
│       └── InvalidIdTokenException.kt
├── application/
│   ├── port/in/command/LoginWithGoogleUseCase.kt
│   ├── port/out/
│   │   ├── UserRepository.kt
│   │   ├── JwtIssuer.kt
│   │   ├── JwtVerifier.kt
│   │   ├── OAuthStateStore.kt
│   │   └── GoogleOAuthClient.kt
│   └── service/command/LoginWithGoogleService.kt
├── adapter/
│   ├── in/rest/
│   │   ├── AuthController.kt
│   │   └── AuthExceptionHandler.kt
│   ├── out/persistence/
│   │   ├── UserDocument.kt
│   │   ├── UserMongoDataRepository.kt
│   │   └── UserMongoRepository.kt
│   └── out/external/
│       ├── Rs256JwtAdapter.kt
│       ├── RedisOAuthStateStore.kt
│       └── GoogleOAuthHttpClient.kt
└── config/
    ├── JwtProperties.kt
    └── GoogleOAuthProperties.kt

apps/auth/src/test/kotlin/com/bara/auth/
├── adapter/
│   ├── in/rest/AuthControllerTest.kt
│   ├── out/external/
│   │   ├── Rs256JwtAdapterTest.kt
│   │   └── RedisOAuthStateStoreTest.kt
│   └── out/persistence/UserMongoRepositoryTest.kt
└── application/service/command/LoginWithGoogleServiceTest.kt
```

### 생성되는 파일 (Frontend)

```
clients/web/
├── package.json
├── pnpm-lock.yaml
├── tsconfig.json
├── tsconfig.node.json
├── vite.config.ts
├── tailwind.config.ts
├── postcss.config.js
├── index.html
├── .gitignore
└── src/
    ├── main.tsx
    ├── App.tsx
    ├── index.css
    ├── pages/
    │   ├── LoginPage.tsx
    │   ├── CallbackPage.tsx
    │   └── MePage.tsx
    ├── lib/
    │   ├── auth.ts
    │   └── jwt.ts
    └── __tests__/
        ├── setup.ts
        ├── auth.test.ts
        └── jwt.test.ts
```

### 수정되는 파일

- `gradle/libs.versions.toml` — 의존성 추가
- `apps/auth/build.gradle.kts` — 의존성 추가
- `apps/auth/src/main/kotlin/com/bara/auth/BaraAuthApplication.kt` — `@ConfigurationPropertiesScan`
- `apps/auth/src/main/resources/application.yml` — jwt/google/redis 설정
- `.gitignore` — `.env`, `*.pem`, `clients/web/node_modules/`, `clients/web/dist/`

### 신규 루트 파일

- `.env.example` — 템플릿 (git 포함)
- `.env` — 로컬 전용 (gitignored)

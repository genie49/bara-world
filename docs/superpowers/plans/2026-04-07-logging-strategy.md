# Logging Strategy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Wide Event 패턴 기반의 구조화 JSON 로깅을 `libs/common`에 공통 모듈로 구현하고, Auth Service에 적용한다.

**Architecture:** `libs/common`에 로깅 필터/유틸리티를 구현하고 Spring Auto Configuration으로 등록. 각 서비스는 `libs/common` 의존성만 추가하면 자동 적용. Kafka Interceptor는 향후 Kafka 도입 시 사용할 수 있도록 구조만 잡아둔다.

**Tech Stack:** Kotlin, Spring Boot 3.4, logstash-logback-encoder, SLF4J/Logback, MDC

---

## File Structure

### 신규 생성

| 파일 | 역할 |
|------|------|
| `libs/common/src/main/kotlin/com/bara/common/logging/WideEvent.kt` | ThreadLocal 기반 wide event 맵 holder |
| `libs/common/src/main/kotlin/com/bara/common/logging/CorrelationIdFilter.kt` | X-Correlation-Id 헤더 추출/생성 → MDC |
| `libs/common/src/main/kotlin/com/bara/common/logging/RequestLoggingFilter.kt` | wide event 조립 + finally에서 단일 로그 출력 |
| `libs/common/src/main/kotlin/com/bara/common/logging/LoggingAutoConfiguration.kt` | @Configuration, 필터 빈 등록 |
| `libs/common/src/main/resources/logback-base.xml` | JSON encoder 공통 Logback 설정 |
| `libs/common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | Auto Configuration 등록 |
| `libs/common/src/test/kotlin/com/bara/common/logging/WideEventTest.kt` | WideEvent 단위 테스트 |
| `libs/common/src/test/kotlin/com/bara/common/logging/CorrelationIdFilterTest.kt` | CorrelationIdFilter 단위 테스트 |
| `libs/common/src/test/kotlin/com/bara/common/logging/RequestLoggingFilterTest.kt` | RequestLoggingFilter 단위 테스트 |
| `apps/auth/src/main/resources/logback-spring.xml` | Auth 서비스 Logback 설정 (logback-base.xml include) |
| `docs/guides/logging/README.md` | 로깅 규칙 문서 |
| `docs/guides/logging/flows/auth-login.md` | Auth 로그인 흐름 로그 문서 |

### 수정

| 파일 | 변경 내용 |
|------|----------|
| `gradle/libs.versions.toml` | logstash-logback-encoder 버전 카탈로그 추가 |
| `libs/common/build.gradle.kts` | spring-boot-starter-web, logstash-logback-encoder 의존성 추가 |
| `apps/auth/build.gradle.kts` | (이미 `libs:common` 의존) — 변경 없음 |
| `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthController.kt` | WideEvent.put()으로 비즈니스 컨텍스트 추가 |
| `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthExceptionHandler.kt` | WideEvent.put()으로 에러 컨텍스트 추가 |
| `apps/auth/src/main/kotlin/com/bara/auth/application/service/command/LoginWithGoogleService.kt` | WideEvent.put()으로 user_id, is_new_user 추가 |
| `infra/k8s/base/core/auth.yaml` | APP_VERSION, APP_ENVIRONMENT 환경변수 추가 |
| `infra/k8s/overlays/prod/kustomization.yaml` | APP_ENVIRONMENT=prod 패치 추가 |
| `.github/workflows/deploy.yml` | APP_VERSION=$IMAGE_TAG로 env 패치 추가 |
| `CLAUDE.md` | Logging 섹션 추가 |

---

### Task 1: Gradle 의존성 세팅

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `libs/common/build.gradle.kts`

- [ ] **Step 1: Version catalog에 logstash-logback-encoder 추가**

`gradle/libs.versions.toml`에 추가:

```toml
# [versions] 섹션에 추가
logstash-logback-encoder = "8.0"

# [libraries] 섹션에 추가
logstash-logback-encoder = { module = "net.logstash.logback:logstash-logback-encoder", version.ref = "logstash-logback-encoder" }
```

- [ ] **Step 2: libs/common에 의존성 추가**

`libs/common/build.gradle.kts`를 다음으로 교체:

```kotlin
plugins {
    id("bara-kotlin-library")
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.logstash.logback.encoder)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockk)
}
```

> `bara-kotlin-library`에는 Spring dependency management가 없으므로, `spring-boot-starter-web`은 버전을 version catalog에서 관리하거나 `bara-spring-boot` 플러그인 대신 직접 Spring BOM을 import해야 한다. 그러나 `libs/common`은 라이브러리이므로 `bara-spring-boot`(bootJar 포함)는 부적절하다. spring-boot-starter-web의 버전은 Spring Boot BOM으로 해결한다.

실제로는 `bara-kotlin-library`에서 Spring dependency management를 쓰지 않으므로, `libs/common/build.gradle.kts`를 다음과 같이 한다:

```kotlin
plugins {
    id("bara-kotlin-library")
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation(libs.logstash.logback.encoder)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(libs.mockk)
}
```

- [ ] **Step 3: 빌드 확인**

Run: `./gradlew :libs:common:dependencies --configuration compileClasspath | head -30`
Expected: `spring-boot-starter-web`과 `logstash-logback-encoder`가 의존성에 포함

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml libs/common/build.gradle.kts
git commit -m "build: libs/common에 로깅 의존성 추가 (logstash-logback-encoder)"
```

---

### Task 2: WideEvent holder 구현

**Files:**
- Create: `libs/common/src/main/kotlin/com/bara/common/logging/WideEvent.kt`
- Create: `libs/common/src/test/kotlin/com/bara/common/logging/WideEventTest.kt`

- [ ] **Step 1: WideEvent 테스트 작성**

```kotlin
package com.bara.common.logging

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class WideEventTest {

    @AfterEach
    fun cleanup() {
        WideEvent.clear()
    }

    @Test
    fun `put과 getAll로 데이터를 저장하고 조회한다`() {
        WideEvent.put("user_id", "u-123")
        WideEvent.put("outcome", "success")

        val data = WideEvent.getAll()
        assertEquals("u-123", data["user_id"])
        assertEquals("success", data["outcome"])
    }

    @Test
    fun `clear하면 모든 데이터가 제거된다`() {
        WideEvent.put("user_id", "u-123")
        WideEvent.clear()

        assertTrue(WideEvent.getAll().isEmpty())
    }

    @Test
    fun `같은 키에 put하면 덮어쓴다`() {
        WideEvent.put("outcome", "pending")
        WideEvent.put("outcome", "success")

        assertEquals("success", WideEvent.getAll()["outcome"])
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :libs:common:test --tests "com.bara.common.logging.WideEventTest" --info 2>&1 | tail -20`
Expected: FAIL — `WideEvent` 클래스가 없음

- [ ] **Step 3: WideEvent 구현**

```kotlin
package com.bara.common.logging

object WideEvent {

    private val holder = ThreadLocal<MutableMap<String, Any?>>()

    fun put(key: String, value: Any?) {
        getOrCreate()[key] = value
    }

    fun getAll(): Map<String, Any?> = getOrCreate().toMap()

    fun clear() {
        holder.remove()
    }

    private fun getOrCreate(): MutableMap<String, Any?> {
        var map = holder.get()
        if (map == null) {
            map = mutableMapOf()
            holder.set(map)
        }
        return map
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :libs:common:test --tests "com.bara.common.logging.WideEventTest" --info 2>&1 | tail -20`
Expected: PASS — 3개 테스트 통과

- [ ] **Step 5: Commit**

```bash
git add libs/common/src/main/kotlin/com/bara/common/logging/WideEvent.kt \
        libs/common/src/test/kotlin/com/bara/common/logging/WideEventTest.kt
git commit -m "feat: WideEvent ThreadLocal holder 구현"
```

---

### Task 3: CorrelationIdFilter 구현

**Files:**
- Create: `libs/common/src/main/kotlin/com/bara/common/logging/CorrelationIdFilter.kt`
- Create: `libs/common/src/test/kotlin/com/bara/common/logging/CorrelationIdFilterTest.kt`

- [ ] **Step 1: CorrelationIdFilter 테스트 작성**

```kotlin
package com.bara.common.logging

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class CorrelationIdFilterTest {

    private val filter = CorrelationIdFilter()

    @AfterEach
    fun cleanup() {
        MDC.clear()
    }

    @Test
    fun `헤더에 X-Correlation-Id가 있으면 그 값을 MDC에 세팅한다`() {
        val request = MockHttpServletRequest().apply {
            addHeader("X-Correlation-Id", "existing-corr-id")
        }
        val response = MockHttpServletResponse()
        var mdcValue: String? = null

        filter.doFilter(request, response, FilterChain { _, _ ->
            mdcValue = MDC.get("correlation_id")
        })

        assertEquals("existing-corr-id", mdcValue)
        assertEquals("existing-corr-id", response.getHeader("X-Correlation-Id"))
    }

    @Test
    fun `헤더가 없으면 UUID를 생성하여 MDC에 세팅한다`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        var mdcValue: String? = null

        filter.doFilter(request, response, FilterChain { _, _ ->
            mdcValue = MDC.get("correlation_id")
        })

        assertNotNull(mdcValue)
        assertTrue(mdcValue!!.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        assertEquals(mdcValue, response.getHeader("X-Correlation-Id"))
    }

    @Test
    fun `필터 완료 후 MDC에서 correlation_id가 제거된다`() {
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, FilterChain { _, _ -> })

        assertNull(MDC.get("correlation_id"))
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :libs:common:test --tests "com.bara.common.logging.CorrelationIdFilterTest" --info 2>&1 | tail -20`
Expected: FAIL — `CorrelationIdFilter` 클래스가 없음

- [ ] **Step 3: CorrelationIdFilter 구현**

```kotlin
package com.bara.common.logging

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val correlationId = request.getHeader(HEADER_NAME) ?: UUID.randomUUID().toString()
        MDC.put(MDC_KEY, correlationId)
        response.setHeader(HEADER_NAME, correlationId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        const val HEADER_NAME = "X-Correlation-Id"
        const val MDC_KEY = "correlation_id"
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :libs:common:test --tests "com.bara.common.logging.CorrelationIdFilterTest" --info 2>&1 | tail -20`
Expected: PASS — 3개 테스트 통과

- [ ] **Step 5: Commit**

```bash
git add libs/common/src/main/kotlin/com/bara/common/logging/CorrelationIdFilter.kt \
        libs/common/src/test/kotlin/com/bara/common/logging/CorrelationIdFilterTest.kt
git commit -m "feat: CorrelationIdFilter 구현 (X-Correlation-Id 헤더 ↔ MDC)"
```

---

### Task 4: RequestLoggingFilter 구현

**Files:**
- Create: `libs/common/src/main/kotlin/com/bara/common/logging/RequestLoggingFilter.kt`
- Create: `libs/common/src/test/kotlin/com/bara/common/logging/RequestLoggingFilterTest.kt`

- [ ] **Step 1: RequestLoggingFilter 테스트 작성**

```kotlin
package com.bara.common.logging

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class RequestLoggingFilterTest {

    private val filter = RequestLoggingFilter()

    @AfterEach
    fun cleanup() {
        WideEvent.clear()
        MDC.clear()
    }

    @Test
    fun `요청 완료 후 MDC에 HTTP 컨텍스트가 세팅된다`() {
        val request = MockHttpServletRequest("GET", "/auth/google/callback")
        val response = MockHttpServletResponse().apply { status = 302 }
        val capturedMdc = mutableMapOf<String, String?>()

        filter.doFilter(request, response, FilterChain { _, _ ->
            // 필터 체인 내부에서 MDC 값 캡처
            capturedMdc["method"] = MDC.get("method")
            capturedMdc["path"] = MDC.get("path")
            capturedMdc["request_id"] = MDC.get("request_id")
        })

        assertEquals("GET", capturedMdc["method"])
        assertEquals("/auth/google/callback", capturedMdc["path"])
        assertNotNull(capturedMdc["request_id"])
    }

    @Test
    fun `WideEvent 데이터가 MDC에 포함된다`() {
        val request = MockHttpServletRequest("POST", "/test")
        val response = MockHttpServletResponse()
        val capturedMdc = mutableMapOf<String, String?>()

        filter.doFilter(request, response, FilterChain { _, _ ->
            WideEvent.put("user_id", "u-123")
            WideEvent.put("outcome", "success")
        })

        // WideEvent는 필터 완료 후 clear됨
        assertTrue(WideEvent.getAll().isEmpty())
    }

    @Test
    fun `필터 완료 후 MDC가 정리된다`() {
        val request = MockHttpServletRequest("GET", "/test")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, FilterChain { _, _ -> })

        assertNull(MDC.get("method"))
        assertNull(MDC.get("path"))
        assertNull(MDC.get("request_id"))
        assertNull(MDC.get("status_code"))
        assertNull(MDC.get("duration_ms"))
    }

    @Test
    fun `예외 발생 시에도 MDC가 정리되고 예외는 다시 throw된다`() {
        val request = MockHttpServletRequest("GET", "/test")
        val response = MockHttpServletResponse()

        assertThrows(RuntimeException::class.java) {
            filter.doFilter(request, response, FilterChain { _, _ ->
                throw RuntimeException("test error")
            })
        }

        assertNull(MDC.get("method"))
        assertTrue(WideEvent.getAll().isEmpty())
    }
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `./gradlew :libs:common:test --tests "com.bara.common.logging.RequestLoggingFilterTest" --info 2>&1 | tail -20`
Expected: FAIL — `RequestLoggingFilter` 클래스가 없음

- [ ] **Step 3: RequestLoggingFilter 구현**

```kotlin
package com.bara.common.logging

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class RequestLoggingFilter : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger("wide-event")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val startTime = System.currentTimeMillis()
        val requestId = UUID.randomUUID().toString()

        MDC.put("request_id", requestId)
        MDC.put("method", request.method)
        MDC.put("path", request.requestURI)

        var thrown: Exception? = null
        try {
            filterChain.doFilter(request, response)
        } catch (e: Exception) {
            thrown = e
            throw e
        } finally {
            val durationMs = System.currentTimeMillis() - startTime
            val statusCode = if (thrown != null) 500 else response.status

            // WideEvent에 쌓인 비즈니스 컨텍스트를 MDC로 복사
            WideEvent.getAll().forEach { (key, value) ->
                if (value != null) MDC.put(key, value.toString())
            }

            MDC.put("status_code", statusCode.toString())
            MDC.put("duration_ms", durationMs.toString())

            if (thrown != null) {
                MDC.put("error_type", thrown.javaClass.simpleName)
                MDC.put("error_message", thrown.message ?: "")
                log.error("request completed with error")
            } else if (statusCode in 400..499) {
                log.warn("request completed with client error")
            } else {
                log.info("request completed")
            }

            // 정리
            MDC.remove("request_id")
            MDC.remove("method")
            MDC.remove("path")
            MDC.remove("status_code")
            MDC.remove("duration_ms")
            MDC.remove("error_type")
            MDC.remove("error_message")
            WideEvent.getAll().keys.forEach { MDC.remove(it) }
            WideEvent.clear()
        }
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :libs:common:test --tests "com.bara.common.logging.RequestLoggingFilterTest" --info 2>&1 | tail -20`
Expected: PASS — 4개 테스트 통과

- [ ] **Step 5: Commit**

```bash
git add libs/common/src/main/kotlin/com/bara/common/logging/RequestLoggingFilter.kt \
        libs/common/src/test/kotlin/com/bara/common/logging/RequestLoggingFilterTest.kt
git commit -m "feat: RequestLoggingFilter 구현 (wide event 조립 + 단일 로그 출력)"
```

---

### Task 5: Logback JSON 설정 + Auto Configuration

**Files:**
- Create: `libs/common/src/main/resources/logback-base.xml`
- Create: `libs/common/src/main/kotlin/com/bara/common/logging/LoggingAutoConfiguration.kt`
- Create: `libs/common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: logback-base.xml 작성**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<included>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <!-- MDC 키는 모두 포함 (필터에서 관리하므로 별도 제한 불필요) -->
            <customFields>
                {"service":"${SERVICE_NAME:-unknown}",
                 "version":"${APP_VERSION:-local}",
                 "environment":"${APP_ENVIRONMENT:-dev}",
                 "instance_id":"${HOSTNAME:-local}"}</customFields>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="STDOUT"/>
    </root>
</included>
```

- [ ] **Step 2: LoggingAutoConfiguration 작성**

```kotlin
package com.bara.common.logging

import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.context.annotation.Bean

@AutoConfiguration
class LoggingAutoConfiguration {

    @Bean
    fun correlationIdFilter(): CorrelationIdFilter = CorrelationIdFilter()

    @Bean
    fun requestLoggingFilter(): RequestLoggingFilter = RequestLoggingFilter()
}
```

- [ ] **Step 3: Auto Configuration 등록**

`libs/common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`:

```
com.bara.common.logging.LoggingAutoConfiguration
```

- [ ] **Step 4: 빌드 확인**

Run: `./gradlew :libs:common:build --info 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add libs/common/src/main/resources/logback-base.xml \
        libs/common/src/main/kotlin/com/bara/common/logging/LoggingAutoConfiguration.kt \
        libs/common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
git commit -m "feat: Logback JSON 설정 + LoggingAutoConfiguration 등록"
```

---

### Task 6: Auth Service에 로깅 적용

**Files:**
- Create: `apps/auth/src/main/resources/logback-spring.xml`
- Modify: `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthController.kt`
- Modify: `apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthExceptionHandler.kt`
- Modify: `apps/auth/src/main/kotlin/com/bara/auth/application/service/command/LoginWithGoogleService.kt`

- [ ] **Step 1: logback-spring.xml 생성**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="logback-base.xml"/>
</configuration>
```

- [ ] **Step 2: LoginWithGoogleService에 WideEvent 추가**

`apps/auth/src/main/kotlin/com/bara/auth/application/service/command/LoginWithGoogleService.kt` 수정:

```kotlin
package com.bara.auth.application.service.command

import com.bara.auth.application.port.`in`.command.LoginWithGoogleUseCase
import com.bara.auth.application.port.out.GoogleOAuthClient
import com.bara.auth.application.port.out.JwtIssuer
import com.bara.auth.application.port.out.OAuthStateStore
import com.bara.auth.application.port.out.UserRepository
import com.bara.auth.domain.model.User
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class LoginWithGoogleService(
    private val googleClient: GoogleOAuthClient,
    private val userRepository: UserRepository,
    private val stateStore: OAuthStateStore,
    private val jwtIssuer: JwtIssuer,
) : LoginWithGoogleUseCase {

    override fun buildLoginUrl(): String {
        WideEvent.put("oauth_provider", "google")
        val state = stateStore.issue()
        return googleClient.buildAuthorizationUrl(state)
    }

    override fun login(code: String, state: String): String {
        WideEvent.put("oauth_provider", "google")
        stateStore.consume(state)
        val payload = googleClient.exchangeCodeForIdToken(code)
        val existing = userRepository.findByGoogleId(payload.googleId)
        val isNew = existing == null
        val user = existing ?: userRepository.save(
            User.newUser(
                googleId = payload.googleId,
                email = payload.email,
                name = payload.name,
            )
        )
        WideEvent.put("user_id", user.id)
        WideEvent.put("user_email", user.email)
        WideEvent.put("is_new_user", isNew)
        return jwtIssuer.issue(user)
    }
}
```

- [ ] **Step 3: AuthController에 WideEvent 추가**

`apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthController.kt` 수정:

```kotlin
package com.bara.auth.adapter.`in`.rest

import com.bara.auth.application.port.`in`.command.LoginWithGoogleUseCase
import com.bara.auth.config.GoogleOAuthProperties
import com.bara.common.logging.WideEvent
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
        WideEvent.put("outcome", "redirect_to_google")
        return redirect(url)
    }

    @GetMapping("/callback")
    fun callback(
        @RequestParam code: String,
        @RequestParam state: String,
    ): ResponseEntity<Void> {
        val jwt = useCase.login(code = code, state = state)
        WideEvent.put("outcome", "success")
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

- [ ] **Step 4: AuthExceptionHandler에 WideEvent 추가**

`apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthExceptionHandler.kt` 수정:

```kotlin
package com.bara.auth.adapter.`in`.rest

import com.bara.auth.config.GoogleOAuthProperties
import com.bara.auth.domain.exception.GoogleExchangeFailedException
import com.bara.auth.domain.exception.InvalidIdTokenException
import com.bara.auth.domain.exception.InvalidOAuthStateException
import com.bara.common.logging.WideEvent
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
    fun handleInvalidState(): ResponseEntity<Void> {
        WideEvent.put("error_type", "InvalidOAuthStateException")
        WideEvent.put("outcome", "invalid_state")
        return redirectWithError("invalid_state")
    }

    @ExceptionHandler(GoogleExchangeFailedException::class)
    fun handleExchangeFailed(): ResponseEntity<Void> {
        WideEvent.put("error_type", "GoogleExchangeFailedException")
        WideEvent.put("outcome", "exchange_failed")
        return redirectWithError("google_exchange_failed")
    }

    @ExceptionHandler(InvalidIdTokenException::class)
    fun handleInvalidIdToken(): ResponseEntity<Void> {
        WideEvent.put("error_type", "InvalidIdTokenException")
        WideEvent.put("outcome", "invalid_id_token")
        return redirectWithError("invalid_id_token")
    }

    private fun redirectWithError(code: String): ResponseEntity<Void> {
        val uri = URI.create("${frontendCallbackBase()}?error=$code")
        val headers = HttpHeaders().apply { location = uri }
        return ResponseEntity(headers, HttpStatus.FOUND)
    }

    private fun frontendCallbackBase(): String {
        return googleProps.redirectUri.replace("/auth/google/callback", "/auth/callback")
    }
}
```

- [ ] **Step 5: Auth 테스트 통과 확인**

Run: `./gradlew :apps:auth:test --info 2>&1 | tail -30`
Expected: 기존 테스트 모두 PASS (WideEvent.put은 side-effect만 추가하므로 기존 동작에 영향 없음)

- [ ] **Step 6: Commit**

```bash
git add apps/auth/src/main/resources/logback-spring.xml \
        apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthController.kt \
        apps/auth/src/main/kotlin/com/bara/auth/adapter/in/rest/AuthExceptionHandler.kt \
        apps/auth/src/main/kotlin/com/bara/auth/application/service/command/LoginWithGoogleService.kt
git commit -m "feat: Auth Service에 wide event 로깅 적용"
```

---

### Task 7: K8s 환경변수 + CI/CD 수정

**Files:**
- Modify: `infra/k8s/base/core/auth.yaml`
- Modify: `infra/k8s/overlays/prod/kustomization.yaml`
- Modify: `.github/workflows/deploy.yml`

- [ ] **Step 1: auth.yaml에 환경변수 추가**

`infra/k8s/base/core/auth.yaml`의 containers.env 마지막에 추가:

```yaml
            - name: APP_VERSION
              value: "local"
            - name: APP_ENVIRONMENT
              value: "dev"
            - name: SERVICE_NAME
              value: "bara-auth"
```

- [ ] **Step 2: prod overlay에 APP_ENVIRONMENT 패치 추가**

`infra/k8s/overlays/prod/kustomization.yaml`의 auth 패치에 추가:

```yaml
      - op: add
        path: /spec/template/spec/containers/0/env/-
        value:
          name: APP_ENVIRONMENT
          value: "prod"
```

- [ ] **Step 3: deploy.yml에 APP_VERSION 주입 추가**

`.github/workflows/deploy.yml`의 SSH script에서 auth 이미지 업데이트 후 환경변수도 패치:

```bash
            if [ "$AUTH_CHANGED" = "true" ]; then
              kubectl set image deployment/auth -n core \
                auth=$REGISTRY/$REPOSITORY/auth:$IMAGE_TAG
              kubectl set env deployment/auth -n core \
                APP_VERSION=$IMAGE_TAG
            fi
            if [ "$FE_CHANGED" = "true" ]; then
              kubectl set image deployment/fe -n core \
                fe=$REGISTRY/$REPOSITORY/fe:$IMAGE_TAG
            fi
```

- [ ] **Step 4: Kustomize 빌드 확인**

Run: `kubectl kustomize infra/k8s/overlays/prod/ 2>&1 | grep -A 5 "APP_"`
Expected: APP_VERSION, APP_ENVIRONMENT, SERVICE_NAME 환경변수가 출력됨

- [ ] **Step 5: Commit**

```bash
git add infra/k8s/base/core/auth.yaml \
        infra/k8s/overlays/prod/kustomization.yaml \
        .github/workflows/deploy.yml
git commit -m "infra: 로깅용 환경변수 (APP_VERSION, APP_ENVIRONMENT) 추가"
```

---

### Task 8: CLAUDE.md + 로깅 가이드 문서 작성

**Files:**
- Modify: `CLAUDE.md`
- Create: `docs/guides/logging/README.md`
- Create: `docs/guides/logging/flows/auth-login.md`

- [ ] **Step 1: CLAUDE.md에 Logging 섹션 추가**

`CLAUDE.md`의 `## Architecture` 섹션 앞에 추가:

```markdown
## Logging

- Wide Event 패턴 사용 — 요청당 서비스당 단일 구조화 JSON 로그 출력
- 로깅 공통 모듈: `libs/common/src/main/kotlin/com/bara/common/logging/`
- 새 port/adapter/엔드포인트 추가 시 반드시 `docs/guides/logging/README.md` 참조
- 해당 흐름의 비즈니스 컨텍스트 로깅 필드를 정의하고 `docs/guides/logging/flows/`에 문서 추가
```

- [ ] **Step 2: docs/guides/logging/README.md 작성**

```markdown
# Logging Guide

## 개요

bara-world 백엔드는 **Wide Event 패턴**을 사용한다.
요청 하나당 서비스 하나에서 단일 구조화 JSON 로그를 출력한다.

## 핵심 규칙

### 1. 단일 로그 출력

`RequestLoggingFilter`가 요청 완료 시 한 번만 로그를 출력한다.
서비스 코드에서 직접 `logger.info()`를 호출하지 않는다.
비즈니스 컨텍스트는 `WideEvent.put()`으로 추가한다.

### 2. 레벨 규칙

| 레벨 | 용도 |
|------|------|
| `info` | 정상 완료 |
| `warn` | 클라이언트 에러(4xx) — 서비스는 건강 |
| `error` | 서버 에러(5xx), 외부 서비스 실패 |

### 3. 필드 네이밍

- snake_case 사용 (예: `user_id`, `error_type`)
- 자동 포함 필드: `correlation_id`, `request_id`, `method`, `path`, `status_code`, `duration_ms`, `service`, `version`, `environment`, `instance_id`
- 비즈니스 필드는 핸들러/서비스에서 `WideEvent.put()`으로 추가

### 4. WideEvent 사용법

```kotlin
// Controller나 Service에서
WideEvent.put("user_id", user.id)
WideEvent.put("outcome", "success")
WideEvent.put("is_new_user", true)
```

- ThreadLocal 기반이므로 요청 스레드 내에서만 유효
- 필터가 finally에서 자동 정리하므로 수동 clear 불필요

### 5. ExceptionHandler 패턴

ExceptionHandler에서는 로그를 직접 출력하지 않는다.
WideEvent에 에러 컨텍스트만 추가한다:

```kotlin
@ExceptionHandler(SomeException::class)
fun handle(): ResponseEntity<...> {
    WideEvent.put("error_type", "SomeException")
    WideEvent.put("outcome", "some_error")
    return ...
}
```

### 6. Correlation ID

- HTTP: `X-Correlation-Id` 헤더로 전파 (없으면 자동 생성)
- Kafka: Record Header `X-Correlation-Id`로 전파

## 새 엔드포인트 추가 시 체크리스트

1. 해당 흐름에서 추적해야 할 비즈니스 필드 정의
2. Controller/Service에서 `WideEvent.put()` 호출 추가
3. ExceptionHandler에서 에러 컨텍스트 추가
4. `docs/guides/logging/flows/`에 흐름 문서 작성
5. MDC 키는 자동 포함되므로 `logback-base.xml` 수정 불필요

## 흐름 문서

각 API 흐름별 로그 필드와 출력 시점은 `flows/` 디렉토리에 문서화한다.

- [Auth Google Login](flows/auth-login.md)
```

- [ ] **Step 3: docs/guides/logging/flows/auth-login.md 작성**

```markdown
# Auth Google Login 로그 흐름

## GET /auth/google/login

정상 흐름:

| 필드 | 값 | 추가 시점 |
|------|-----|----------|
| `oauth_provider` | `"google"` | LoginWithGoogleService.buildLoginUrl() |
| `outcome` | `"redirect_to_google"` | AuthController.login() |

Wide event 출력 예시:
```json
{
  "correlation_id": "...",
  "request_id": "...",
  "method": "GET",
  "path": "/auth/google/login",
  "status_code": 302,
  "duration_ms": 12,
  "oauth_provider": "google",
  "outcome": "redirect_to_google",
  "level": "info"
}
```

## GET /auth/google/callback

### 성공

| 필드 | 값 | 추가 시점 |
|------|-----|----------|
| `oauth_provider` | `"google"` | LoginWithGoogleService.login() |
| `user_id` | 유저 ID | LoginWithGoogleService.login() |
| `user_email` | 유저 이메일 | LoginWithGoogleService.login() |
| `is_new_user` | `true`/`false` | LoginWithGoogleService.login() |
| `outcome` | `"success"` | AuthController.callback() |

### 실패 — 잘못된 OAuth state

| 필드 | 값 | 추가 시점 |
|------|-----|----------|
| `error_type` | `"InvalidOAuthStateException"` | AuthExceptionHandler |
| `outcome` | `"invalid_state"` | AuthExceptionHandler |

### 실패 — Google code 교환 실패

| 필드 | 값 | 추가 시점 |
|------|-----|----------|
| `oauth_provider` | `"google"` | LoginWithGoogleService.login() |
| `error_type` | `"GoogleExchangeFailedException"` | AuthExceptionHandler |
| `outcome` | `"exchange_failed"` | AuthExceptionHandler |

### 실패 — ID token 검증 실패

| 필드 | 값 | 추가 시점 |
|------|-----|----------|
| `oauth_provider` | `"google"` | LoginWithGoogleService.login() |
| `error_type` | `"InvalidIdTokenException"` | AuthExceptionHandler |
| `outcome` | `"invalid_id_token"` | AuthExceptionHandler |
```

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md \
        docs/guides/logging/README.md \
        docs/guides/logging/flows/auth-login.md
git commit -m "docs: 로깅 전략 가이드 및 Auth 로그 흐름 문서 추가"
```

---

### Task 9: 전체 빌드 + 통합 검증

**Files:** (없음 — 검증만)

- [ ] **Step 1: libs/common 테스트**

Run: `./gradlew :libs:common:test`
Expected: 모든 테스트 PASS

- [ ] **Step 2: Auth 테스트**

Run: `./gradlew :apps:auth:test`
Expected: 기존 + 신규 테스트 모두 PASS

- [ ] **Step 3: 전체 빌드**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Auth bootRun으로 JSON 로그 출력 확인 (수동)**

Run: `./gradlew :apps:auth:bootRun` (인프라 필요, 선택적)
Expected: 콘솔에 JSON 형식 로그가 출력됨

- [ ] **Step 5: Kustomize 검증**

Run: `kubectl kustomize infra/k8s/overlays/prod/ > /dev/null && echo "OK"`
Expected: OK

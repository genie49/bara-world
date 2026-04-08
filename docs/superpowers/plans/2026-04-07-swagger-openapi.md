# Swagger/OpenAPI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SpringDoc OpenAPI를 공통 모듈에 추가하여 모든 Spring Boot 서비스에 Swagger UI를 자동 제공한다.

**Architecture:** `libs/common`에 springdoc 의존성과 OpenAPI AutoConfiguration을 추가한다. 서비스별 title/version은 프로퍼티로 주입. Traefik에서 Swagger 경로를 public으로 노출하고, prod는 프로퍼티로 비활성화한다.

**Tech Stack:** SpringDoc OpenAPI 2.8.6, Spring Boot 3.4.4, Traefik IngressRoute

---

## File Structure

### New Files

| File                                                                              | Responsibility                                    |
| --------------------------------------------------------------------------------- | ------------------------------------------------- |
| `libs/common/src/main/kotlin/com/bara/common/openapi/OpenApiAutoConfiguration.kt` | OpenAPI Bean 등록, 프로퍼티 기반 서비스 정보 주입 |
| `libs/common/src/main/kotlin/com/bara/common/openapi/OpenApiProperties.kt`        | `bara.openapi.*` 프로퍼티 바인딩                  |

### Modified Files

| File                                                                                                              | Change                             |
| ----------------------------------------------------------------------------------------------------------------- | ---------------------------------- |
| `gradle/libs.versions.toml`                                                                                       | springdoc 버전 및 라이브러리 추가  |
| `libs/common/build.gradle.kts`                                                                                    | springdoc 의존성 `api`로 추가      |
| `libs/common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | OpenApiAutoConfiguration 등록      |
| `apps/auth/src/main/resources/application.yml`                                                                    | bara.openapi + springdoc 설정 추가 |
| `infra/k8s/base/gateway/routes.yaml`                                                                              | auth-public에 swagger 경로 추가    |

---

### Task 1: 의존성 추가 및 공통 모듈 설정

**Files:**

- Modify: `gradle/libs.versions.toml`
- Modify: `libs/common/build.gradle.kts`

- [ ] **Step 1: `libs.versions.toml`에 springdoc 추가**

`[versions]` 섹션에 추가:

```toml
springdoc = "2.8.6"
```

`[libraries]` 섹션에 추가:

```toml
springdoc-openapi-webmvc-ui = { module = "org.springdoc:springdoc-openapi-starter-webmvc-ui", version.ref = "springdoc" }
```

- [ ] **Step 2: `libs/common/build.gradle.kts`에 의존성 추가**

`dependencies` 블록에 추가:

```kotlin
api(libs.springdoc.openapi.webmvc.ui)
```

`api`로 선언하여 이 모듈을 의존하는 모든 서비스가 자동으로 springdoc을 사용할 수 있게 한다.

- [ ] **Step 3: 빌드 확인**

Run: `./gradlew :libs:common:build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml libs/common/build.gradle.kts
git commit -m "feat(infra): add springdoc-openapi dependency to common module"
```

---

### Task 2: OpenAPI AutoConfiguration

**Files:**

- Create: `libs/common/src/main/kotlin/com/bara/common/openapi/OpenApiProperties.kt`
- Create: `libs/common/src/main/kotlin/com/bara/common/openapi/OpenApiAutoConfiguration.kt`
- Modify: `libs/common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: `OpenApiProperties.kt` 생성**

```kotlin
package com.bara.common.openapi

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "bara.openapi")
data class OpenApiProperties(
    val title: String = "Bara API",
    val version: String = "0.0.1",
    val description: String = "",
)
```

- [ ] **Step 2: `OpenApiAutoConfiguration.kt` 생성**

```kotlin
package com.bara.common.openapi

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

@AutoConfiguration
@ConditionalOnClass(OpenAPI::class)
@EnableConfigurationProperties(OpenApiProperties::class)
class OpenApiAutoConfiguration {

    @Bean
    fun openApi(props: OpenApiProperties): OpenAPI =
        OpenAPI().info(
            Info()
                .title(props.title)
                .version(props.version)
                .description(props.description)
        )
}
```

- [ ] **Step 3: AutoConfiguration imports에 등록**

`libs/common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 파일에 한 줄 추가:

```
com.bara.common.logging.LoggingAutoConfiguration
com.bara.common.openapi.OpenApiAutoConfiguration
```

- [ ] **Step 4: 빌드 확인**

Run: `./gradlew :libs:common:build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add libs/common/src/main/kotlin/com/bara/common/openapi/ libs/common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
git commit -m "feat(infra): add OpenAPI auto-configuration in common module"
```

---

### Task 3: Auth Service 설정 및 확인

**Files:**

- Modify: `apps/auth/src/main/resources/application.yml`

- [ ] **Step 1: `application.yml`에 OpenAPI 프로퍼티 추가**

`bara:` 섹션의 `auth:` 뒤에 추가:

```yaml
bara:
  openapi:
    title: Bara Auth API
    version: 1.0.0
    description: 인증/인가 서비스 API
  auth:
    # ... 기존 설정 유지
```

- [ ] **Step 2: 기존 테스트 통과 확인**

Run: `./gradlew :apps:auth:test`
Expected: 전체 PASS

- [ ] **Step 3: bootRun으로 Swagger UI 접근 확인**

Run: `./gradlew :apps:auth:bootRun` (별도 터미널)

브라우저에서 `http://localhost:8081/api/auth/swagger-ui.html` 접근:

- Swagger UI가 표시되어야 함
- "Bara Auth API" 타이틀, 버전 1.0.0 표시
- 모든 컨트롤러 엔드포인트가 자동으로 나열

`http://localhost:8081/api/auth/v3/api-docs` 접근:

- OpenAPI 3.0 JSON spec 반환

bootRun 종료.

- [ ] **Step 4: Commit**

```bash
git add apps/auth/src/main/resources/application.yml
git commit -m "feat(auth): add OpenAPI configuration for Swagger UI"
```

---

### Task 4: Traefik 라우팅 추가

**Files:**

- Modify: `infra/k8s/base/gateway/routes.yaml`

- [ ] **Step 1: `auth-public` IngressRoute에 Swagger 경로 추가**

`routes.yaml`의 `auth-public` IngressRoute의 match 줄을 변경:

기존:

```yaml
- match: PathPrefix(`/api/auth/google`) || Path(`/api/auth/refresh`) || Path(`/api/auth/validate`)
```

변경:

```yaml
- match: PathPrefix(`/api/auth/google`) || Path(`/api/auth/refresh`) || Path(`/api/auth/validate`) || PathPrefix(`/api/auth/swagger-ui`) || PathPrefix(`/api/auth/v3/api-docs`)
```

- [ ] **Step 2: Commit**

```bash
git add infra/k8s/base/gateway/routes.yaml
git commit -m "feat(infra): add Swagger UI routes to Traefik public IngressRoute"
```

---

### Task 5: prod 비활성화 설정

**Files:**

- Modify: `.env.example` (또는 해당 환경변수 문서)

- [ ] **Step 1: `.env.example`에 prod 비활성화 변수 추가**

파일 끝에 추가:

```
# Swagger (prod에서 비활성화)
SPRINGDOC_SWAGGER_UI_ENABLED=false
SPRINGDOC_API_DOCS_ENABLED=false
```

참고: Spring Boot는 `SPRINGDOC_SWAGGER_UI_ENABLED`를 자동으로 `springdoc.swagger-ui.enabled`로 바인딩한다. prod 배포 시 이 환경변수를 `.env.prod`에 추가하면 된다.

- [ ] **Step 2: Commit**

```bash
git add .env.example
git commit -m "docs(infra): add Swagger disable env vars for prod"
```

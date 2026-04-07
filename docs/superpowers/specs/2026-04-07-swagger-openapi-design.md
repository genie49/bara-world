# Swagger/OpenAPI 설정 설계

## 개요

SpringDoc OpenAPI를 사용하여 모든 Spring Boot 서비스에 Swagger UI와 OpenAPI 3.0 spec을 자동 생성한다. 공통 설정은 `libs/common`에, 서비스별 설정은 각 서비스의 `application.yml`에 배치한다.

## 결정 사항

| 항목           | 결정                                        |
| -------------- | ------------------------------------------- |
| 라이브러리     | `springdoc-openapi-starter-webmvc-ui:2.8.6` |
| 공통 모듈      | `libs/common`에 의존성 + AutoConfiguration  |
| Traefik 라우팅 | public 경로로 노출 (forwardAuth 미적용)     |
| prod 비활성화  | Spring 프로퍼티로 비활성화 (환경변수)       |

## 의존성

`gradle/libs.versions.toml`에 추가:

```toml
[versions]
springdoc = "2.8.6"

[libraries]
springdoc-openapi-webmvc-ui = { module = "org.springdoc:springdoc-openapi-starter-webmvc-ui", version.ref = "springdoc" }
```

`libs/common/build.gradle.kts`에서 `api` 의존성으로 노출하여 이를 의존하는 모든 서비스가 자동으로 Swagger UI를 사용할 수 있게 한다.

## 공통 설정 (libs/common)

`libs/common/src/main/kotlin/com/bara/common/openapi/OpenApiAutoConfiguration.kt`

- `@Configuration` + `@ConditionalOnClass(OpenAPI::class)`
- `OpenAPI` Bean 등록: title, version, description을 `bara.openapi.*` 프로퍼티에서 읽음
- 프로퍼티가 없으면 기본값 사용 (`Bara API`, `0.0.1`)

## Auth Service 설정

`apps/auth/src/main/resources/application.yml`에 추가:

```yaml
bara:
  openapi:
    title: Bara Auth API
    version: 1.0.0
    description: 인증/인가 서비스 API

springdoc:
  swagger-ui:
    path: /swagger-ui.html
  api-docs:
    path: /v3/api-docs
```

context-path가 `/api/auth`이므로 실제 접근 경로:

- Swagger UI: `http://localhost:8081/api/auth/swagger-ui.html`
- OpenAPI spec: `http://localhost:8081/api/auth/v3/api-docs`

## prod 비활성화

서비스의 prod 환경변수에 추가:

```
SPRINGDOC_SWAGGER_UI_ENABLED=false
SPRINGDOC_API_DOCS_ENABLED=false
```

서비스가 404를 반환하므로 Traefik 라우팅을 별도로 제거할 필요 없음.

## Traefik 라우팅

`infra/k8s/base/gateway/routes.yaml`의 `auth-public` IngressRoute match에 Swagger 경로 추가:

```
PathPrefix(`/api/auth/swagger-ui`) || PathPrefix(`/api/auth/v3/api-docs`)
```

forwardAuth 미적용, CORS만 적용. 기존 public 경로(`/api/auth/google`, `/api/auth/refresh`, `/api/auth/validate`)와 동일한 미들웨어 구성.

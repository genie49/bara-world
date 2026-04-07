# Logging Guide

## 개요

bara-world 백엔드는 **Wide Event 패턴**을 사용한다.
요청 하나당 서비스 하나에서 단일 구조화 로그를 출력한다.

## 로그 포맷

- **dev** (기본): 사람이 읽기 쉬운 텍스트 포맷
- **prod** (`SPRING_PROFILES_ACTIVE=prod`): JSON 구조화 포맷 (logstash-logback-encoder)

설정 파일:
- `libs/common/src/main/resources/logback-dev.xml` — dev 텍스트 포맷
- `libs/common/src/main/resources/logback-base.xml` — prod JSON 포맷
- 각 서비스의 `logback-spring.xml`에서 `<springProfile>`로 분기

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
// 비즈니스 컨텍스트 추가
WideEvent.put("user_id", user.id)
WideEvent.put("outcome", "success")
WideEvent.put("is_new_user", true)

// 사람이 읽을 수 있는 로그 메시지 (필수)
WideEvent.message("Google OAuth 콜백 성공")
```

- `WideEvent.put()` — MDC에 포함되는 구조화 필드
- `WideEvent.message()` — 로그의 메시지 본문. 미설정 시 `"GET /path"` 형태로 fallback
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
    WideEvent.message("설명적인 에러 메시지")
    return ...
}
```

### 6. Correlation ID

- HTTP: `X-Correlation-Id` 헤더로 전파 (없으면 자동 생성)
- Kafka: Record Header `X-Correlation-Id`로 전파

## 새 엔드포인트 추가 시 체크리스트

1. 해당 흐름에서 추적해야 할 비즈니스 필드 정의
2. Controller/Service에서 `WideEvent.put()` + `WideEvent.message()` 호출 추가
3. ExceptionHandler에서 에러 컨텍스트 + 메시지 추가
4. `docs/guides/logging/flows/`에 흐름 문서 작성
5. MDC 키는 자동 포함되므로 `logback-base.xml` 수정 불필요

## 새 서비스 추가 시 체크리스트

1. `build.gradle.kts`에 `implementation(project(":libs:common"))` 의존성 추가
2. `src/main/resources/logback-spring.xml` 생성 (springProfile로 dev/prod 분기, auth 참고)
3. K8s manifest에 환경변수 추가: `SERVICE_NAME`, `APP_VERSION`, `APP_ENVIRONMENT`
4. prod overlay에 `APP_ENVIRONMENT=prod`, `SPRING_PROFILES_ACTIVE=prod` 패치 추가
5. `deploy.yml`에 해당 서비스의 `APP_VERSION` 주입 추가

## 흐름 문서

각 API 흐름별 로그 필드와 출력 시점은 `flows/` 디렉토리에 문서화한다.

- [Auth Google Login](flows/auth-login.md)

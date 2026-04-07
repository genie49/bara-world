# 로깅 전략 설계

## 개요

bara-world 백엔드 플랫폼의 관측성(Observability) 중심 로깅 전략.
Wide Event 패턴을 기반으로 요청당 서비스당 단일 구조화 JSON 로그를 출력한다.

**범위:** 백엔드 전체 (Auth + 향후 API/Scheduler), FE 제외
**목적:** 디버깅 + 성능 모니터링 + 비즈니스 메트릭
**수집 인프라:** 현재 stdout + `kubectl logs`, 향후 Loki/Grafana 등 확장 가능
**포맷:** 운영/개발 모두 JSON

## 핵심 결정

| 항목 | 결정 |
|------|------|
| 패턴 | Wide Event — 요청당 서비스당 단일 구조화 로그 |
| 레벨 | info(정상), warn(4xx/클라이언트), error(5xx/서버) |
| 추적 | requestId + correlationId, HTTP 헤더 + Kafka Record Header 전파 |
| Agent 추적 | SDK에 correlationId 전파 기본 포함, best-effort |
| 라이브러리 | logstash-logback-encoder + OncePerRequestFilter |
| 모듈 위치 | `libs/common/logging`에 공통 모듈 |
| 환경변수 | `APP_VERSION`, `APP_ENVIRONMENT` 2개 추가 |
| 문서화 | `docs/guides/logging/` 에 규칙 + 흐름별 문서 관리 |

## 1. 공통 로깅 인프라 (`libs/common`)

모든 백엔드 서비스가 공유하는 로깅 기반을 `libs/common`에 둔다.

### 의존성

- `logstash-logback-encoder` — JSON 구조화 출력
- SLF4J + Logback — Spring Boot 기본 (추가 불필요)

### 공통 컴포넌트

| 컴포넌트 | 역할 |
|---------|------|
| `RequestLoggingFilter` | `OncePerRequestFilter`. 요청 시작 시 wide event 맵 생성, MDC에 requestId/correlationId 세팅, finally에서 단일 JSON 로그 출력 |
| `CorrelationIdFilter` | `X-Correlation-Id` 헤더 → MDC 추출. 없으면 UUID 생성. 응답 헤더에도 포함 |
| `WideEvent` | ThreadLocal 기반 `MutableMap<String, Any?>` holder. 핸들러/서비스에서 비즈니스 컨텍스트 추가용 |
| `KafkaCorrelationInterceptor` | Producer: MDC → Record Header 복사. Consumer: Record Header → MDC 복사 |
| `logback-base.xml` | 공통 Logback 설정 (JSON encoder, 환경변수 기반 필드 주입) |

### 모듈 구조

```
libs/common/src/main/kotlin/com/bara/common/logging/
├── WideEvent.kt                    # ThreadLocal<MutableMap> holder
├── CorrelationIdFilter.kt          # X-Correlation-Id 추출/생성 → MDC
├── RequestLoggingFilter.kt         # wide event 조립 + finally에서 출력
├── LoggingAutoConfiguration.kt     # @Configuration, 필터 빈 등록
└── kafka/
    ├── CorrelationProducerInterceptor.kt   # MDC → Record Header
    └── CorrelationConsumerInterceptor.kt   # Record Header → MDC

libs/common/src/main/resources/
└── logback-base.xml                # JSON encoder 공통 설정
```

### 필터 순서

1. `CorrelationIdFilter` (order: 0) — correlationId 확보
2. `RequestLoggingFilter` (order: 1) — wide event 생성, 타이밍, 출력

### 서비스에서의 사용

```xml
<!-- apps/auth/src/main/resources/logback-spring.xml -->
<configuration>
  <include resource="logback-base.xml"/>
</configuration>
```

서비스별 추가 Logback 설정 불필요. include만 하면 된다.

### 환경 필드

모든 wide event에 자동 포함:

| 필드 | 소스 |
|------|------|
| `service` | `spring.application.name` (이미 설정됨) |
| `version` | `APP_VERSION` 환경변수 (CI/CD에서 `github.sha` 주입), 로컬 기본값 `local` |
| `environment` | `APP_ENVIRONMENT` 환경변수 (Kustomize overlay에서 주입), 기본값 `dev` |
| `instance_id` | `HOSTNAME` 환경변수 (K8s pod name, 자동 존재) |

## 2. Wide Event 구조

요청 하나당 서비스 하나에서 단일 JSON 로그를 출력한다.

### 필드 스키마

```json
{
  "correlation_id": "550e8400-...",
  "request_id": "7c9e6679-...",
  "timestamp": "2026-04-07T12:00:00.000Z",

  "method": "GET",
  "path": "/auth/google/callback",
  "status_code": 302,
  "duration_ms": 142,

  "service": "bara-auth",
  "version": "abc1234",
  "environment": "prod",
  "instance_id": "auth-7b9f4d-xk2p",

  "user_id": "6612f...",
  "user_email": "kim@example.com",
  "outcome": "success",

  "error_type": "GoogleExchangeFailedException",
  "error_message": "Google code 교환 실패: invalid_grant",

  "level": "info"
}
```

### 레벨 규칙

| 레벨 | 용도 | 예시 |
|------|------|------|
| `info` | 정상 완료 | 로그인 성공, 토큰 발급 |
| `warn` | 클라이언트 에러(4xx), 서비스는 건강 | 잘못된 OAuth state, 만료된 토큰 |
| `error` | 서버 에러(5xx), 외부 서비스 실패 | Google API 장애, DB 연결 실패 |

### 핸들러에서의 사용

```kotlin
@GetMapping("/callback")
fun callback(@RequestParam code: String, @RequestParam state: String): ResponseEntity<Void> {
    val jwt = useCase.login(code = code, state = state)
    WideEvent.put("outcome", "success")
    return redirect("${frontendCallbackBase()}?token=$jwt")
}
```

필터가 method, path, status_code, duration_ms, error 등을 자동으로 채우고 finally에서 한 번만 출력한다.
핸들러는 비즈니스 컨텍스트만 `WideEvent.put()`으로 추가한다.

## 3. Correlation ID 전파 흐름

### HTTP 동기 흐름

```
Client → Traefik → Service
                   │
                   ├─ X-Correlation-Id 헤더가 있으면 그대로 사용
                   └─ 없으면 CorrelationIdFilter에서 UUID 생성

                   Service 내부: MDC에 correlationId 세팅
                   응답 헤더에도 X-Correlation-Id 포함 (디버깅 편의)
```

### Kafka 비동기 흐름

```
API Service                          Agent (SDK)
    │                                    │
    ├─ Producer Interceptor              │
    │  MDC.correlationId                 │
    │  → Record Header                  │
    │    "X-Correlation-Id"              │
    │                                    │
    │         ──── Kafka ────→           │
    │                                    ├─ SDK가 헤더에서 추출
    │                                    ├─ 응답 메시지에 동일 헤더 복사
    │         ←──── Kafka ────           │
    │                                    │
    ├─ Consumer Interceptor              │
    │  Record Header                     │
    │  → MDC.correlationId              │
    │  → Wide Event에 자동 포함          │
```

### 설계 원칙

- 첫 번째 서비스의 `CorrelationIdFilter`가 correlationId 생성 책임
- Kafka 전파는 Record Header 사용 — 메시지 본문(payload) 오염 없음
- Agent SDK는 best-effort — 헤더를 돌려주는 게 기본 동작이지만, 안 돌려와도 우리 서비스 로그는 정상 작동

## 4. 서비스별 비즈니스 컨텍스트

### Auth Service

| 필드 | 시점 | 예시 |
|------|------|------|
| `oauth_provider` | 로그인 시작 | `"google"` |
| `user_id` | 로그인 성공 후 | `"6612f..."` |
| `user_email` | 로그인 성공 후 | `"kim@example.com"` |
| `is_new_user` | 회원가입/로그인 구분 | `true` / `false` |
| `outcome` | 요청 완료 시 | `"success"`, `"invalid_state"`, `"exchange_failed"` |

### API Service (향후)

| 필드 | 시점 | 예시 |
|------|------|------|
| `user_id` | JWT 인증 후 | `"6612f..."` |
| `agent_id` | Agent 호출 시 | `"agent-123"` |
| `task_id` | 태스크 생성 시 | `"task-456"` |
| `task_type` | 태스크 유형 | `"sync"`, `"sse"` |

### Scheduler Service (향후)

| 필드 | 시점 | 예시 |
|------|------|------|
| `schedule_id` | 스케줄 실행 시 | `"sched-789"` |
| `agent_id` | 대상 Agent | `"agent-123"` |
| `trigger_type` | 트리거 유형 | `"cron"` |

새 port/adapter/엔드포인트 추가 시 해당 흐름의 비즈니스 컨텍스트 필드를 정의하고 `docs/guides/logging/flows/`에 문서를 추가한다.

## 5. 에러 로깅 전략

### 원칙

ExceptionHandler에서 직접 로깅하지 않는다. `RequestLoggingFilter`의 finally 블록이 유일한 로그 출력 지점.

### 동작 흐름

```
요청 → CorrelationIdFilter → RequestLoggingFilter → Controller → Service
                                    │
                                    ├─ 정상: status 302, level=info
                                    ├─ 비즈니스 예외(4xx): ExceptionHandler가 응답 처리
                                    │   → WideEvent에 error_type, error_message 세팅
                                    │   → Filter가 status 보고 level=warn
                                    └─ 서버 에러(5xx): 미처리 예외
                                        → Filter가 catch, stack trace 포함
                                        → level=error
```

### ExceptionHandler 패턴

```kotlin
@ExceptionHandler(InvalidOAuthStateException::class)
fun handleInvalidState(): ResponseEntity<Void> {
    WideEvent.put("error_type", "InvalidOAuthStateException")
    WideEvent.put("outcome", "invalid_state")
    return redirectWithError("invalid_state")
}
```

ExceptionHandler의 역할은 응답 생성 + wide event에 비즈니스 컨텍스트 추가.
로그 출력은 필터가 담당하여 요청당 정확히 1줄을 유지한다.

## 6. 환경변수 주입

### 추가되는 환경변수

| 변수 | 용도 | 주입 방식 |
|------|------|----------|
| `APP_VERSION` | commit hash | CI/CD에서 `${{ github.sha }}` → Deployment env |
| `APP_ENVIRONMENT` | `dev` / `prod` | Kustomize overlay에서 패치 |

### 로컬 개발

기본값 fallback (`version=local`, `environment=dev`). `.env`에 추가 불필요.

### K8s Deployment 패치 (prod overlay)

```yaml
- op: add
  path: /spec/template/spec/containers/0/env/-
  value:
    name: APP_VERSION
    value: "will-be-overridden-by-deploy"
- op: add
  path: /spec/template/spec/containers/0/env/-
  value:
    name: APP_ENVIRONMENT
    value: "prod"
```

`APP_VERSION`은 `kubectl set image` 이후 별도 `kubectl set env`로 주입하거나, deploy.yml에서 직접 패치.

## 7. 로깅 문서화

### 문서 구조

```
docs/guides/logging/
├── README.md              # 로깅 규칙 총정리
└── flows/
    ├── auth-login.md      # Google 로그인 흐름의 로그 필드/시점
    └── ...                # 흐름 추가 시 문서 추가
```

### README.md 내용

- Wide event 패턴 설명 + 예시
- 레벨 규칙 (info/warn/error)
- 필드 네이밍 컨벤션 (snake_case)
- 새 port/adapter 추가 시 체크리스트
- `WideEvent.put()` 사용법
- flows/ 디렉토리에 흐름 문서 추가하는 방법

### CLAUDE.md 추가

```markdown
## Logging
- 새 port/adapter/엔드포인트 추가 시 반드시 `docs/guides/logging/README.md` 참조
- 해당 흐름의 비즈니스 컨텍스트 로깅 필드를 정의하고 `docs/guides/logging/flows/`에 문서 추가
```

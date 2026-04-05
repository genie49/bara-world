# Bara World

Google A2A(Agent2Agent) 프로토콜 기반의 멀티 에이전트 플랫폼.

다양한 환경에 배포된 AI Agent들이 표준화된 방식으로 통신하고, 사용자는 웹 또는 텔레그램을 통해 Agent와 대화할 수 있다.

## 컨셉

- **어디서든 Agent 연결** — Agent 서버가 AWS, GCP, Railway 등 어디에 있든 Provider 토큰과 공용 SDK만 있으면 플랫폼에 연동
- **A2A 프로토콜 준수** — Google A2A 표준을 따르는 Agent Card, 태스크 관리, SSE 스트리밍, 멀티턴 대화
- **채널 확장** — 웹, 텔레그램 등 다양한 채널로 Agent에 접근. 새 채널 추가 시 공용 SDK만 사용하면 됨
- **Kafka 기반 비동기 통신** — Agent와의 모든 통신은 Kafka를 통해 비동기로 처리. 장애 시 메시지 유실 없음

## 주요 기능

### Agent 관리

- Agent 등록/조회 (Redis heartbeat 기반 자동 발견)
- Agent Card proxy (안정적인 외부 URL 제공)
- Kafka 계정/ACL 자동 생성

### 태스크 처리

- 동기 요청 (`POST /tasks`) + 폴링
- 스트리밍 요청 (`POST /tasks:stream`) + SSE 실시간 응답
- SSE backfill — 연결 끊김 시 Redis 버퍼링, 재연결 시 replay
- 태스크 수명 관리 — 활성(3일) → 만료(7일) → MongoDB TTL 자동 삭제

### 멀티턴 대화

- A2A 표준 `context_id` 기반 대화 연속성
- 채널에 무관하게 동일한 방식으로 동작

### 스케줄링

- Agent 응답의 `extensions.schedule`로 반복 작업 등록
- Scheduler Service가 cron에 맞춰 Agent에 태스크 발행
- 결과를 원래 채널(webhook 또는 텔레그램)로 자동 전달

### 인증

- 사용자: Google OAuth → 자체 JWT
- Provider: Auth Service 가입 → Provider 토큰
- Kafka: OAUTHBEARER 단기 토큰 (1시간, 자동 갱신)

### 보안

- Cloudflare Full Strict TLS
- Nginx 헤더 초기화 후 재주입 (스푸핑 방지)
- Kafka SASL + ACL (토픽별 접근 제어)
- JWT RS256 + JTI 1회용 (replay 방지)

### 로깅/모니터링

- Fluent Bit → Kafka → Loki → Grafana
- `X-Request-Id`로 에이전트 체인 전체 추적
- 보안/운영 알람 (인증 실패, Agent 다운, 응답 지연)

### 공용 SDK

- Python, TypeScript, Java 지원
- Kafka 통신, OAUTHBEARER 인증, 메시지 포맷팅, 에러 처리
- 수신 토픽 기반 결과 라우팅 자동 처리, `extensions.schedule` 감지

## 기술 스택

| 영역           | 기술                                |
| -------------- | ----------------------------------- |
| Protocol       | Google A2A                          |
| API / Auth     | Kotlin + Spring Boot                |
| Messaging      | Apache Kafka (KRaft)                |
| Database       | MongoDB                             |
| Cache          | Redis                               |
| Reverse Proxy  | Nginx                               |
| CDN / TLS      | Cloudflare (Free)                   |
| Container      | K3s (단일 노드)                     |
| Logging        | Fluent Bit → Kafka → Loki → Grafana |
| Infrastructure | OCI Ampere A1 (ARM64, 무료 티어)    |
| SDK            | Python, TypeScript, Java            |

## 설계 문서

상세 설계는 [docs/spec/](docs/spec/) 참고.

### 전체 / 공통

| 문서                                                                    | 내용                                        |
| ----------------------------------------------------------------------- | ------------------------------------------- |
| [overview](docs/spec/overview.md)                                       | 전체 아키텍처, 구성 요소, 기술 스택         |
| [shared/infrastructure](docs/spec/shared/infrastructure.md)             | VM, K3s, Cloudflare, Nginx, 네트워크        |
| [shared/messaging](docs/spec/shared/messaging.md)                       | Kafka 통신, SSE, 에러, 스케줄, 태스크 수명  |
| [shared/security](docs/spec/shared/security.md)                         | 위협 매트릭스, 계층별 대응, 우선순위 로드맵 |
| [shared/logging](docs/spec/shared/logging.md)                           | 로그 파이프라인, Grafana, 알람              |
| [shared/service-architecture](docs/spec/shared/service-architecture.md) | 헥사고날 아키텍처, CQRS 가이드              |
| [shared/project-structure](docs/spec/shared/project-structure.md)       | 모노레포 구조, 빌드, 배포                   |

### 서비스별

| 문서                                                    | 내용                                         |
| ------------------------------------------------------- | -------------------------------------------- |
| [auth/authentication](docs/spec/auth/authentication.md) | Google OAuth, JWT, Provider 토큰, Kafka 토큰 |
| [api/agent-registry](docs/spec/api/agent-registry.md)   | Agent 등록, Redis, Agent Card, 태스크 처리   |
| [scheduler/scheduler](docs/spec/scheduler/scheduler.md) | 스케줄 등록/실행, Webhook                    |
| [clients/fe](docs/spec/clients/fe.md)                   | 웹 FE                                        |
| [clients/telegram](docs/spec/clients/telegram.md)       | 텔레그램, 멀티턴 대화                        |

### 기록

| 문서                                          | 내용                                              |
| --------------------------------------------- | ------------------------------------------------- |
| [decisions/](docs/spec/decisions/)            | Architecture Decision Records (ADR-001 ~ ADR-012) |
| [superpowers/specs/](docs/superpowers/specs/) | 기능별 구현 스펙 (brainstorming 결과물)           |
| [superpowers/plans/](docs/superpowers/plans/) | 기능별 구현 계획 (task 단위)                      |

## 로컬 개발

Auth Service 로컬 실행 가이드: [docs/guides/auth-local-setup.md](docs/guides/auth-local-setup.md)

기본 흐름:

```bash
# 1. 인프라 (MongoDB, Redis, Kafka)
./scripts/infra.sh up dev

# 2. Auth Service
./gradlew :apps:auth:bootRun

# 3. Frontend
cd clients/web && pnpm install && pnpm dev
```

Git 컨벤션: [docs/guides/git-convention.md](docs/guides/git-convention.md)

# 에이전트 레지스트리

## API Service 역할

API Service는 에이전트의 등록, 조회, 발견을 담당하는 중앙 서비스이며, 외부 사용자에게 A2A 프로토콜 호환 HTTP 인터페이스를 제공하는 **A2A 게이트웨이** 역할을 한다. Agent 서버는 HTTP를 노출하지 않으며, 모든 Agent 통신은 Kafka를 통해 이루어진다.

### 핵심 기능

- Agent 등록 → MongoDB에 영구 저장 + agent_id 발급
- 활성 Agent 목록 조회 (MongoDB 정보 + Redis heartbeat 조합)
- Agent Card 저장/제공 (등록 시 수신, MongoDB에서 조회)
- Agent 등록 시 Kafka 계정 + ACL 자동 생성
- A2A 호환 HTTP 엔드포인트 제공 (사용자 → API Service → Kafka → Agent)

### 엔드포인트

| 엔드포인트                                | 용도                            |
| ----------------------------------------- | ------------------------------- |
| `GET /agents`                             | 활성 Agent 전체 목록            |
| `GET /agents/{id}`                        | 특정 Agent 정보                 |
| `GET /agents/{id}/.well-known/agent.json` | Agent Card 조회 (MongoDB)       |
| `POST /agents`                            | Agent 등록 (Provider 인증 필요) |
| `DELETE /agents/{id}`                     | Agent 삭제 (Provider 인증 필요) |
| `POST /agents/{id}/credentials/rotate`    | Kafka 계정 재발급               |

## 태스크 처리

API Service와 Telegram Service는 공용 SDK를 사용하여 Kafka를 통해 Agent와 직접 통신한다. 채널 서비스가 `tasks.{agent-id}` 토픽에 발행하고, 각 채널 전용 고정 토픽(`results.api`, `results.telegram`)을 상시 구독한다.

### 통신 흐름

```mermaid
sequenceDiagram
    participant Ch as 채널 서비스<br/>(API / Telegram)
    participant K as Kafka
    participant A as Agent 서버

    Ch->>K: tasks.{agent-id} 발행
    K-->>A: 태스크 수신
    A->>A: 처리
    A->>K: results.{채널} 발행 (result_topic 기반)
    K-->>Ch: 결과 수신 → 사용자에게 전달
```

> 토픽 설계, 메시지 구조, ACL 등 Kafka 통신 상세는 [메시징 문서](../shared/messaging.md) 참고.

### API Service 태스크 엔드포인트

| 엔드포인트                                   | 용도                                            |
| -------------------------------------------- | ----------------------------------------------- |
| `POST /agents/{id}/tasks`                    | 태스크 생성 → Kafka 발행, task_id 반환 (비동기) |
| `POST /agents/{id}/tasks:stream`             | 태스크 생성 + 응답 자체가 SSE 스트림            |
| `GET /agents/{id}/tasks/{task_id}`           | 태스크 상태/결과 조회 (history 포함 가능)       |
| `POST /agents/{id}/tasks/{task_id}:cancel`   | 태스크 취소                                     |
| `GET /agents/{id}/tasks/{task_id}:subscribe` | 끊어진 SSE 스트림 재연결 (backfill 지원)        |

## MongoDB Agent 모델 (영구)

| 필드        | 설명                             |
| ----------- | -------------------------------- |
| agent_id    | 시스템 발급 ID (UUID, 식별자)    |
| name        | 표시용 이름 (변경 가능)          |
| provider_id | 소속 Provider                    |
| agent_card  | Agent Card JSON (A2A 메타데이터) |
| skills      | 스킬 목록                        |
| created_at  | 등록 시간                        |

Agent 등록 시 MongoDB에 영구 저장되며, `agent_id`가 시스템 전체의 식별자가 된다. 토픽명(`tasks.{agent_id}`), ACL, 화이트리스트 모두 이 ID를 사용한다.

## Redis 설계 (Heartbeat 전용)

### 키 구조

```
Key:   heartbeat:{agent_id}
Value: 1  (존재 여부만 확인)
TTL:   60초
```

Redis는 Agent의 **활성 여부만** 관리한다. Agent 메타데이터는 MongoDB에 저장.

### 활성 Agent 조회

MongoDB에서 Agent 정보 조회 + Redis에서 `heartbeat:{agent_id}` 존재 여부 확인 = 활성 Agent 목록.

### Heartbeat

| 항목        | 값                    |
| ----------- | --------------------- |
| TTL         | 60초                  |
| 갱신 주기   | 20초                  |
| 정상 종료   | 즉시 `DEL`            |
| 비정상 종료 | TTL 만료 시 자동 제거 |

Agent 서버는 Kafka `heartbeat` 토픽에 주기적으로 메시지를 발행한다. API Service가 이를 구독하여 Redis에 TTL을 갱신한다. Agent가 죽으면 Kafka 발행이 멈추고, 60초 후 TTL 만료로 비활성 처리된다.

> Heartbeat 토픽 상세는 [메시징 문서](../shared/messaging.md#토픽-설계) 참고.

## Agent Card (A2A 스펙)

Agent Card는 등록 시 Provider가 제출하며, MongoDB에 저장된다. Agent 서버는 HTTP를 노출하지 않으므로 API Service가 Agent Card를 대신 제공한다.

### 포함 정보

| 필드            | 설명                                       |
| --------------- | ------------------------------------------ |
| id              | Agent 고유 식별자                          |
| name            | Agent 이름                                 |
| version         | 버전                                       |
| skills          | 제공하는 스킬 목록 (id, name, description) |
| securitySchemes | 지원하는 인증 방식                         |

### 외부 접근 URL

사용자/다른 Agent는 API Service를 통해 Agent Card에 접근한다.

```mermaid
sequenceDiagram
    participant C as 클라이언트
    participant R as API Service
    participant Mongo as MongoDB

    C->>R: GET /api/agents/{id}/.well-known/agent.json
    R->>Mongo: Agent Card 조회
    Mongo-->>R: Agent Card JSON
    R-->>C: Agent Card JSON
```

API Service가 A2A 표준 URL(`/.well-known/agent.json`)을 제공하므로, 외부에서는 표준 A2A 프로토콜과 동일하게 Agent Card에 접근할 수 있다.

## Agent 등록 흐름

Provider(사람/시스템)가 REST API로 Agent를 등록하고, Kafka 자격증명을 받아 Agent 서버에 설정한다. Agent 서버 자체는 HTTP를 노출하지 않는다.

```mermaid
sequenceDiagram
    participant P as Provider
    participant R as API Service
    participant Mongo as MongoDB
    participant K as Kafka

    P->>R: POST /agents<br/>Authorization: Bearer {provider_token}<br/>Body: { name, skills, agent_card }

    R->>R: Agent Card 검증 (필수 필드, 서명)
    R->>Mongo: Agent + Agent Card 영구 저장 → agent_id 발급
    R->>K: Kafka 계정 + ACL 생성

    R-->>P: 등록 완료 + Kafka 접속 정보 (최초 1회)

    P->>P: Agent 서버에 Kafka 접속 정보 설정

    Note over R,K: Agent 서버가 Kafka 연결 후

    loop 20초마다
        participant A as Agent 서버
        A->>K: heartbeat 토픽 발행
        K-->>R: heartbeat 수신
        R->>R: Redis TTL 갱신
    end
```

> Kafka 비밀번호는 최초 등록 응답에만 포함된다. 이후 재조회 불가. 재발급은 `POST /agents/{id}/credentials/rotate`로 명시적 요청 필요.

## 등록 시 검증

### 등록 시 검증 (POST /agents)

| 검증 항목                 | 실패 시          |
| ------------------------- | ---------------- |
| Provider 토큰 유효        | 401 Unauthorized |
| Provider status가 ACTIVE  | 403 Forbidden    |
| Agent Card 필수 필드 포함 | 등록 거부        |
| Agent Card 서명 검증 통과 | 등록 거부        |

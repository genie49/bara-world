# API Service Task Processing 설계

## 개요

API Service의 Task Processing을 완성한다. 현재 `POST /agents/{agentName}/message:send`는 Kafka `tasks.{agentId}`에 발행만 하고 `taskId`만 반환하는 미완성 상태다. 이번 이터레이션에서 A2A 프로토콜 표준에 맞춰 **블로킹 동기 / 비동기+폴링 / SSE 스트리밍** 세 가지 호출 모드를 모두 지원하도록 완성한다.

### 목표

1. **블로킹 동기**: `message/send` 기본 모드. 결과가 터미널 상태에 도달할 때까지 대기 후 A2A Task 객체 반환 (최대 30초).
2. **비동기 + 폴링**: `message/send` + `returnImmediately=true`. 즉시 `submitted` Task 반환 → 클라이언트가 `GET /tasks/{id}`로 폴링.
3. **SSE 스트리밍**: `message/stream`. 진행 상태(`submitted` → `working` → `completed`/...)를 실시간 SSE로 전송. `Last-Event-ID` 기반 재연결(`:subscribe`) 지원.

### 전제

- A2A 공식 스펙: `message/send`의 기본 동작은 블로킹이다 (`returnImmediately: false`가 default). 결과 반환까지 서버가 대기한다.
- 현재 default agent는 `results.api` Kafka 토픽에 A2A `TaskResult` 포맷으로 결과를 발행한다(이미 구현 완료).
- API Service에 Kafka ResultConsumer가 아직 없다. 이게 빠진 핵심 컴포넌트다.
- 설계 문서 `2026-04-09-agent-registry-redesign-design.md`가 이 흐름의 일부("결과 대기")를 설계해놨지만 미구현이다. 이 문서는 그 위에 SSE와 폴링을 더해 A2A 전체 호출 모드를 커버한다.

### 스코프 외 (명시적 제외)

- `POST /agents/{agentName}/tasks/{taskId}:cancel` — 다음 이터레이션
- `input-required`, `auth-required` 상태 처리 — 다음 이터레이션 (enum에서도 제외, Agent가 보내오면 `failed`로 변환)
- 멀티턴 히스토리 조회 (`historyLength` 파라미터)
- Kafka DLQ
- Micrometer metric / Grafana 대시보드
- A2A `pushNotificationConfig` (webhook)
- Redis Stream 이벤트 de-dup (at-least-once 허용)

---

## §1. 아키텍처 개요

### 핵심 통찰 — "세 경로, 하나의 이벤트 소스"

블로킹 / 폴링 / SSE 세 경로가 **같은 이벤트 스트림을 공유**한다. 이게 정합성의 열쇠다. 각 경로를 독립적으로 구현하면 인스턴스 간 브릿지, 레이스 조건, 멱등성이 각각 따로 터진다. 반대로 "이벤트를 한 번만 저장하고 세 경로가 그걸 읽게" 하면 한 구조로 해결된다.

### 컴포넌트 블록도

```
┌──────────────────────────── API Service 인스턴스 ────────────────────────────┐
│                                                                              │
│  AgentController                                                             │
│    ├─ POST message:send  (blocking / returnImmediately)                     │
│    ├─ POST message:stream (SSE)                                              │
│    ├─ GET  tasks/{id}                                                        │
│    └─ GET  tasks/{id}:subscribe (SSE 재연결)                                 │
│                        │                                                     │
│                        ▼                                                     │
│  Application Services                                                        │
│    ├─ SendMessageService (블로킹/async 분기)                                 │
│    ├─ StreamMessageService (SSE)                                             │
│    ├─ GetTaskService                                                         │
│    └─ SubscribeTaskService                                                   │
│                        │                                                     │
│                        ├────────────────────┐                                │
│                        ▼                    ▼                                │
│  TaskEventBusPort                   TaskPublisherPort                        │
│  (공통 백본 — 세 경로가 공유)         → TaskKafkaPublisher                    │
│    ├─ publish(taskId, event)            tasks.{agentId}                      │
│    ├─ subscribe(taskId, fromId)                                              │
│    ├─ await(taskId, timeout)                                                 │
│    └─ close(taskId)                                                          │
│         ▲                                                                    │
│         │                                                                    │
│  ResultConsumerAdapter                                                       │
│    Kafka results.api (shared consumer group)                                 │
│    수신 시: ① Mongo upsert  ② EventBus publish  ③ offset commit              │
│                        │                                                     │
│                        ▼                                                     │
│  TaskRepositoryPort → TaskMongoAdapter                                       │
│    tasks 컬렉션 (영구, TTL 7일)                                               │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
            ▲                                          ▲
Kafka results.api                           Redis Stream stream:task:{taskId}
(Agent → API)                               (TaskEventBus 구현체,
                                             인스턴스 간 브릿지 + backfill)
```

### 컴포넌트 책임

| 컴포넌트 | 책임 |
|---|---|
| `AgentController` | HTTP 요청 수신, User JWT에서 userId 추출, DTO ↔ UseCase 변환, SSE 응답 관리(`SseEmitter`) |
| `SendMessageService` | Task 생성(Mongo insert `submitted`), Kafka 발행, `returnImmediately` 분기: false면 `TaskEventBus.await()`, true면 즉시 반환 |
| `StreamMessageService` | Task 생성 + Kafka 발행 + `TaskEventBus.subscribe()` → SSE emitter에 이벤트 flow |
| `GetTaskService` | `TaskRepository.findById()` 조회, 권한 체크(userId 일치) |
| `SubscribeTaskService` | 기존 Task 존재 확인 + `TaskEventBus.subscribe(fromId=Last-Event-ID or "0")` 재연결 |
| `TaskPublisherPort` / `TaskKafkaPublisher` | `tasks.{agentId}` 발행 (ack 대기 5초, 실패 시 예외) |
| `TaskRepositoryPort` / `TaskMongoAdapter` | MongoDB `tasks` 컬렉션 CRUD |
| `TaskEventBusPort` / `RedisStreamTaskEventBus` | 이벤트 publish/subscribe/await/backfill 공통 API |
| `ResultConsumerAdapter` | Kafka `results.api` 구독, Mongo upsert + EventBus publish |

### 저장소 역할 분리

**규율**: 저장소를 섞지 않는다. 각자 하나의 목적만.

| 저장소 | 용도 | 생명주기 |
|---|---|---|
| **MongoDB `tasks`** | Task 영구 이력(status, input/status 메시지, artifacts, created/updated/completed/expired_at) | TTL Index로 7일 후 자동 삭제 (`expired_at`) |
| **Redis `stream:task:{taskId}`** | 실시간 이벤트 스트림 (**블로킹 await / SSE / backfill 공통 소스**) | 터미널 상태 도달 + 60초 유예 후 `DEL`, 이후 조회는 MongoDB |
| **In-memory `pendingTasks`** | **사용하지 않는다**. 모든 "현재 인스턴스 대기" 로직이 Redis Stream으로 이동 |

**왜 `pendingTasks` 맵을 버리는가**: 수평 확장 시 "다른 인스턴스가 Kafka 결과를 받은 경우" 깨어날 방법이 없다. Redis Stream 하나로 통일하면 인스턴스 간 브릿지 / backfill / SSE 버퍼 / 블로킹 대기가 **한 구조로 해결**된다.

### Kafka Consumer Group 전략 — Shared group

| 전략 | unique per instance | **shared (채택)** |
|---|---|---|
| `group-id` | `results.api.${uuid}` | `api-service-results` |
| 결과 수신 | 모든 인스턴스가 모두 수신 | 파티션 분배, 각 결과 한 인스턴스만 |
| MongoDB 쓰기 | N배 중복 | 1회 |
| 인스턴스 간 브릿지 | 필요 없음(로컬 pendingTasks) | **Redis Stream이 담당** |
| SSE backfill | 별도 구조 필요 | **Redis Stream 자체가 backfill** |

**shared 채택 이유**: Redis Stream이 이미 SSE backfill용으로 필수 → 인스턴스 간 브릿지가 공짜로 생김 → unique group의 단순함이 사라짐. MongoDB 중복 쓰기도 없어짐. 정석적인 Kafka consumer 패턴과도 일치.

**offset commit**: `enable-auto-commit=false`, 메시지 처리(Mongo upsert + EventBus publish) 완료 후 수동 commit. 처리 실패 시 재소비(at-least-once). 멱등성은 Mongo `$set` + Redis Stream ID 기반 de-dup으로 확보.

---

## §2. 데이터 모델 & 포트 인터페이스

### 2.1 Task 상태 머신

A2A 공식 `TaskState` enum을 따른다. 터미널/비터미널 구분이 핵심.

```
           ┌─────────────┐
           │  submitted  │  ◄── message/send 직후 Mongo insert
           └──────┬──────┘
                  │ Agent가 처리 시작
                  ▼
           ┌─────────────┐
           │   working   │  ─── (진행 중 상태 업데이트 가능)
           └──────┬──────┘
                  │
                  ├────────────────┬──────────────┬──────────────┐
                  ▼                ▼              ▼              ▼
           ┌─────────────┐  ┌───────────┐  ┌──────────┐   ┌──────────┐
           │  completed  │  │  failed   │  │ canceled │   │ rejected │   ◄── 터미널 (final:true)
           └─────────────┘  └───────────┘  └──────────┘   └──────────┘
```

| 상태 | final | 의미 | 저장소 동작 |
|---|---|---|---|
| `submitted` | false | 발행 직후 | Mongo insert + EventBus publish |
| `working` | false | Agent 처리 중 | Mongo update + EventBus publish |
| `completed` | **true** | 정상 완료 | Mongo update(+completedAt, expiredAt) + EventBus publish + 스트림 close(60s 후) |
| `failed` | **true** | 처리 실패 | 〃 |
| `canceled` | **true** | Agent 스스로 취소(API 호출 없음, 수신만 수용) | 〃 |
| `rejected` | **true** | Agent 거부 | 〃 |

**스코프 외 상태**: `input-required`, `auth-required`. Agent가 보내오면 `failed` + `errorCode="unsupported-state"`로 변환 기록(보호 장치).

### 2.2 Task 도메인 모델

```kotlin
// domain/model/Task.kt
data class Task(
    val id: TaskId,                    // UUID v4
    val agentId: AgentId,
    val agentName: String,              // 조회 편의상 denormalize
    val userId: UserId,
    val contextId: String,              // A2A context_id
    val state: TaskState,
    val inputMessage: A2AMessage,        // 사용자가 보낸 메시지
    val statusMessage: A2AMessage? = null,  // 현재 상태의 agent 응답
    val artifacts: List<A2AArtifact> = emptyList(),
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val requestId: String,              // 로그 correlation
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant? = null,
    val expiredAt: Instant,             // TTL Index 대상
)

enum class TaskState {
    SUBMITTED, WORKING, COMPLETED, FAILED, CANCELED, REJECTED;

    val isTerminal: Boolean get() = this in setOf(
        COMPLETED, FAILED, CANCELED, REJECTED
    )
}
```

### 2.3 MongoDB `tasks` 컬렉션

```
{
  _id: "uuid",
  agentId: "...",
  agentName: "default-agent",
  userId: "...",
  contextId: "...",
  state: "completed",
  inputMessage: { messageId, role: "user", parts: [...] },
  statusMessage: { messageId, role: "agent", parts: [...] },
  artifacts: [],
  errorCode: null,
  errorMessage: null,
  requestId: "...",
  createdAt: ISODate,
  updatedAt: ISODate,
  completedAt: ISODate,
  expiredAt: ISODate
}
```

**인덱스**:

| 인덱스 | 목적 |
|---|---|
| `_id` (기본) | `GET /tasks/{id}` 조회 |
| `{ expiredAt: 1 }` TTL `expireAfterSeconds: 0` | MongoDB 백그라운드 자동 삭제 |

`expiredAt`은 `createdAt + 7일` 또는 터미널 상태 진입 시 `completedAt + 7일`로 갱신. TTL Index 백그라운드 작업은 60초 주기.

### 2.4 Redis Stream 이벤트

**키**: `stream:task:{taskId}`

**XADD 엔트리 구조**: 단일 필드 `event`에 전체 JSON 저장. Redis Stream 엔트리 ID(`<timestamp>-<seq>`)가 SSE `Last-Event-ID`와 backfill 오프셋으로 쓰인다.

```
XADD stream:task:{taskId} * event <json>
```

**이벤트 JSON**:

```json
{
  "taskId": "task-uuid",
  "contextId": "ctx-uuid",
  "state": "working",
  "statusMessage": { "messageId": "...", "role": "agent", "parts": [{"kind":"text","text":"..."}] },
  "artifact": null,
  "errorCode": null,
  "errorMessage": null,
  "final": false,
  "timestamp": "2026-04-09T10:00:00.123Z"
}
```

**스트림 수명**:

| 시점 | 동작 |
|---|---|
| Task 생성 | 스트림 미생성 (첫 XADD에서 자동 생성) |
| `submitted` 이벤트 | `SendMessageService`/`StreamMessageService`가 XADD |
| `working` 등 중간 이벤트 | `ResultConsumerAdapter`가 XADD |
| `final:true` 이벤트 | `ResultConsumerAdapter`가 XADD, 이후 60초 유예 후 `DEL` |

**60초 유예 이유**: SSE 재연결(`:subscribe`)이 터미널 직후에 도착해도 backfill 가능하도록. 60초 경과 후의 재연결은 `STREAM_UNSUPPORTED` 에러(-32067)로 유도, MongoDB 조회로 전환.

### 2.5 Port 인터페이스

```kotlin
interface TaskRepositoryPort {
    fun save(task: Task): Task
    fun findById(id: TaskId): Task?
    fun findByIdAndUserId(id: TaskId, userId: UserId): Task?
    fun updateState(
        id: TaskId,
        state: TaskState,
        statusMessage: A2AMessage?,
        updatedAt: Instant,
        completedAt: Instant? = null,
        expiredAt: Instant? = null,
    ): Boolean
    fun markError(
        id: TaskId,
        state: TaskState,
        errorCode: String?,
        errorMessage: String?,
        updatedAt: Instant,
        completedAt: Instant,
        expiredAt: Instant,
    ): Boolean
}

interface TaskEventBusPort {
    /** 이벤트 발행. 반환: Redis Stream entry ID */
    fun publish(taskId: TaskId, event: TaskEvent): String

    /**
     * 스트림 구독.
     * fromStreamId:
     *   "$"  — 새 이벤트만 (일반 SSE 연결)
     *   "0"  — 처음부터 (재연결 backfill, 블로킹 await)
     *   "<id>" — 특정 offset 이후 (SSE Last-Event-ID 재연결)
     * listener는 백그라운드 스레드에서 호출됨.
     */
    fun subscribe(
        taskId: TaskId,
        fromStreamId: String,
        listener: (TaskEvent) -> Unit,
    ): Subscription

    /**
     * 블로킹 대기 편의 API. 내부 구현:
     *   subscribe(taskId, "0") { event -> if (event.final) future.complete(event) }
     *   future.orTimeout(timeout).whenComplete { _, _ -> subscription.close() }
     */
    fun await(taskId: TaskId, timeout: Duration): CompletableFuture<TaskEvent>

    /** 터미널 이후 60초 유예 후 DEL */
    fun close(taskId: TaskId)
}

interface Subscription : AutoCloseable {
    override fun close()
}

interface TaskPublisherPort {
    /**
     * Kafka tasks.{agentId} 발행. ack 대기 (send().get(5s)).
     * 실패 시 KafkaPublishException throw.
     */
    fun publish(agentId: AgentId, taskMessage: TaskMessagePayload): SendResult
}

data class TaskMessagePayload(
    val taskId: String,
    val contextId: String,
    val userId: String,
    val requestId: String,
    val resultTopic: String,           // "results.api"
    val allowedAgents: List<String>,    // 현재는 빈 배열
    val message: A2AMessage,
)
```

**`await`이 race 조건을 해결하는 방법**: Redis Stream은 pub/sub가 아니라 영구 데이터 구조다. `await`는 내부적으로 `subscribe(fromStreamId="0")`로 backfill부터 읽는다. Kafka publish보다 subscribe 등록이 늦어도 이미 발행된 이벤트를 놓치지 않는다.

### 2.6 A2A JSON-RPC DTO (wire 포맷)

```kotlin
// adapter/in/rest/a2a/
data class JsonRpcRequest<T>(
    val jsonrpc: String = "2.0",
    val id: JsonNode? = null,
    val method: String? = null,
    val params: T,
)

data class JsonRpcResponse<T>(
    val jsonrpc: String = "2.0",
    val id: JsonNode? = null,
    val result: T? = null,
    val error: JsonRpcError? = null,
)

data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: Map<String, Any?>? = null,
)

data class A2ATaskDto(
    val id: String,
    val contextId: String,
    val status: A2ATaskStatusDto,
    val artifacts: List<A2AArtifactDto> = emptyList(),
    val kind: String = "task",
    val metadata: Map<String, Any?> = emptyMap(),
)

data class A2ATaskStatusDto(
    val state: String,              // "completed" 등 (하이픈 표기)
    val message: A2AMessageDto? = null,
    val timestamp: String? = null,
)
```

Domain `Task` ↔ `A2ATaskDto` 변환은 `A2ATaskMapper`에서 담당. Domain은 enum + Instant, DTO는 String(A2A 와이어 포맷).

### 2.7 에러 코드 테이블

A2A 스펙은 구체 숫자를 강제하지 않는다. JSON-RPC Server error 범위(-32000 ~ -32099)에서 내부적으로 정의.

| 코드 | 상수 | 상황 | HTTP |
|---|---|---|---|
| -32001 | `KAFKA_PUBLISH_FAILED` | Kafka 발행 실패(ack timeout/error) | 502 |
| -32062 | `AGENT_UNAVAILABLE` | Redis registry에 없음 | 503 |
| -32063 | `AGENT_TIMEOUT` | 블로킹 대기 30초 초과 | 504 |
| -32064 | `TASK_NOT_FOUND` | GET/`:subscribe`에서 taskId 없음 | 404 |
| -32065 | `TASK_ACCESS_DENIED` | userId 불일치 | 403 |
| -32067 | `STREAM_UNSUPPORTED` | `:subscribe`인데 스트림 만료(60초 경과) | 410 |

---

## §3. 데이터 흐름

### 시나리오 A — 블로킹 `message/send` (레퍼런스)

```
Client  AgentCtrl  SendMsgSvc  Redis-Reg  TaskRepo  EventBus  TaskPub  Kafka  ResultConsumer  Mongo  Agent
  │         │          │           │          │        │        │       │         │          │       │
  │POST     │          │           │          │        │        │       │         │          │       │
  │send     │          │           │          │        │        │       │         │          │       │
  ├────────▶│          │           │          │        │        │       │         │          │       │
  │         │sendMsg() │           │          │        │        │       │         │          │       │
  │         ├─────────▶│           │          │        │        │       │         │          │       │
  │         │          │registry   │          │        │        │       │         │          │       │
  │         │          │확인       │          │        │        │       │         │          │       │
  │         │          ├──────────▶│          │        │        │       │         │          │       │
  │         │          │◀──agentId─┤          │        │        │       │         │          │       │
  │         │          │                                                                              │
  │         │          │ ① Task(submitted) save                                                       │
  │         │          ├──────────────────────▶│        │        │       │         │          │       │
  │         │          │                                                                              │
  │         │          │ ② EventBus.publish(submitted)                                                │
  │         │          ├────────────────────────────────▶│       │       │         │          │       │
  │         │          │                                                                              │
  │         │          │ ③ await(taskId, 30s)  [subscribe fromId="0"]                                 │
  │         │          ├────────────────────────────────▶│       │       │         │          │       │
  │         │          │      (backfill: submitted 수신 → final=false → 무시)                         │
  │         │          │                                                                              │
  │         │          │ ④ TaskPublisher.publish (ack 대기 5s)                                        │
  │         │          ├──────────────────────────────────────────▶│      │         │          │       │
  │         │          │                                           │send  │         │          │       │
  │         │          │                                           ├─────▶│         │          │       │
  │         │          │                                                  │consume  │          │       │
  │         │          │                                                  ├────────▶│(처리)    │       │
  │         │          │                                                  │         │◀─────────┤       │
  │         │          │                                                  │         │produce   │       │
  │         │          │                                                  │         │results.api       │
  │         │          │                                                  │◀────────┤          │       │
  │         │          │                                                  ├────────▶│          │       │
  │         │          │                                                           │ResultCons│       │
  │         │          │                                                                              │
  │         │          │ ⑤ ResultConsumer.onMessage()                                                 │
  │         │          │ ⑥ TaskRepo.updateState(completed, completedAt, expiredAt)                    │
  │         │          │                                                           │─────────▶│       │
  │         │          │ ⑦ EventBus.publish(completed, final=true)                                   │
  │         │          │                                           │◀────────┤      │          │       │
  │         │          │                                           │ XADD    │      │          │       │
  │         │          │ ⑧ Kafka commitSync()                                                        │
  │         │          │                                                                              │
  │         │          │ ⑨ EventBus subscribe callback: final=true → future.complete                 │
  │         │          │◀──────────── future completes ────────────│        │      │          │       │
  │         │          │                                                                              │
  │         │          │ ⑩ subscription.close(), Mapper→A2ATaskDto                                   │
  │         │◀─────────┤                                                                              │
  │         │HTTP 200  │                                                                              │
  │◀────────┤JsonRpc   │                                                                              │
  │         │Response  │                                                                              │
```

**정합성 포인트**:

1. **④(Kafka publish)보다 ③(subscribe 등록)이 반드시 먼저**. 순서가 뒤집혀도 `fromStreamId="0"` backfill이 이미 발행된 이벤트를 읽으므로 레이스 원천 차단.
2. **Kafka publish 실패**(④) 시: 즉시 `Task` 상태를 `failed`로 update → `EventBus.publish(failed)` → subscription.close() → 502 응답.
3. **Kafka offset commit 순서**: Mongo upsert(⑥) → EventBus publish(⑦) → commitSync(⑧). 순서 뒤집히면 재시작 시 이벤트 유실 가능.
4. **멱등성**: `updateState`는 `$set` 기반 → 같은 이벤트 재처리 시 동일 결과. XADD 중복은 가능하지만 consumer 쪽에서 Stream entry ID로 de-dup 가능(이번 스코프 외).
5. **`submitted` 이벤트도 XADD한다**: 나중에 `:subscribe`로 붙는 SSE 구독자가 backfill로 전체 흐름을 볼 수 있게.

### 시나리오 B — 비동기 `message/send` (`returnImmediately=true`) + 폴링

```
[send 단계]
Client → Ctrl → Svc → ① Mongo save submitted
                    → ② EventBus publish submitted
                    → ③ Kafka publish + ack
                    → 즉시 A2ATaskDto(state="submitted") 응답
      ← HTTP 200 JsonRpcResponse{result: {id, contextId, state: "submitted"}}

(백그라운드: Agent 처리 → ResultConsumer → Mongo update + EventBus publish,
 시나리오 A의 ⑤~⑧과 동일)

[polling 단계]
Client → GET /agents/{name}/tasks/{taskId}
       → Ctrl → GetTaskService
       → TaskRepo.findByIdAndUserId(taskId, userId)
         ├─ 없음 → 404 TASK_NOT_FOUND
         ├─ userId 불일치 → 403 TASK_ACCESS_DENIED
         └─ 반환
       → A2ATaskMapper.toDto(task)
       ← HTTP 200 JsonRpcResponse{result: {state: "working"|"completed", ...}}
```

**정합성 포인트**:

1. 비동기 모드에서도 `submitted` 이벤트를 EventBus에 publish → 나중에 `:subscribe`로 붙으면 backfill 가능.
2. `await()` 호출하지 않음. SendMessageService 분기: `request.configuration?.returnImmediately == true`면 publish 후 즉시 `TaskRepo.findById()`로 다시 읽어 DTO 변환.
3. `GET /tasks/{id}`는 **X-User-Id 체크 필수** (UUID v4가 추측 어렵지만 방어 레이어 유지).
4. 폴링 주기는 클라이언트 책임. 서버 rate limit 없음.

### 시나리오 C — SSE `message/stream`

```
Client  AgentCtrl  StreamSvc  Redis-Reg  TaskRepo  EventBus  TaskPub  Kafka  ResultConsumer  Mongo  Agent
  │         │          │          │          │        │        │       │         │          │       │
  │POST     │          │          │          │        │        │       │         │          │       │
  │stream   │          │          │          │        │        │       │         │          │       │
  ├────────▶│          │          │          │        │        │       │         │          │       │
  │         │SseEmitter│          │          │        │        │       │         │          │       │
  │         │(30분)    │          │          │        │        │       │         │          │       │
  │         ├─────────▶│          │          │        │        │       │         │          │       │
  │         │          │registry확인                                                                  │
  │         │          ├─────────▶│          │        │        │       │         │          │       │
  │         │          │save submitted                                                                │
  │         │          ├─────────────────────▶│        │        │       │         │          │       │
  │         │          │publish submitted                                                             │
  │         │          ├───────────────────────────────▶│       │       │         │          │       │
  │         │          │subscribe(fromId="0") → Subscription                                          │
  │         │          │  listener = { event → emitter.send(event) }                                  │
  │         │          ├───────────────────────────────▶│       │       │         │          │       │
  │         │          │     (backfill: submitted 즉시 emit)                                          │
  │◀────────┼emitter.send(submitted)                                                                  │
  │         │          │                                                                              │
  │         │          │Kafka publish (ack 대기)                                                       │
  │         │          ├─────────────────────────────────────────▶│      │         │          │       │
  │         │          │                                          │send  │         │          │       │
  │         │          │                                          ├─────▶│         │          │       │
  │         │          │                                                 │consume  │          │       │
  │         │          │                                                 ├────────▶│(처리)    │       │
  │         │          │                                                 │         │working 이벤트     │
  │         │          │                                                 │         │◀─────────┤       │
  │         │          │                                                 │◀────────┤          │       │
  │         │          │                                                 ├────────▶│          │       │
  │         │          │                                                          │EventBus  │       │
  │         │          │                                                          │publish   │       │
  │         │          │◀──subscribe callback──────────│        │       │         │(working) │       │
  │◀────────┼emitter.send(working)                                                                    │
  │         │          │                                                                              │
  │         │          │                                  (completed 이벤트도 동일)                    │
  │         │          │◀──subscribe callback (final=true)                                            │
  │◀────────┼emitter.send(completed, final:true)                                                      │
  │         │          │emitter.complete() + subscription.close()                                     │
  │         │          │EventBus.close(taskId) (60s 후 DEL)                                           │
```

**SSE 응답 헤더**:

```
HTTP/1.1 200 OK
Content-Type: text/event-stream
Cache-Control: no-cache
X-Accel-Buffering: no
```

**이벤트 프레임**:

```
id: 1712665200000-0
event: message
data: {"jsonrpc":"2.0","id":"req-1","result":{"id":"task-uuid","contextId":"...","status":{"state":"working","message":{...}},"final":false,"kind":"task"}}

```

**정합성 포인트**:

1. **Spring MVC `SseEmitter`** 사용. `timeout = 1,800,000ms` (30분). `onTimeout`/`onCompletion`/`onError` 콜백에서 subscription.close().
2. EventBus subscribe callback은 백그라운드 스레드에서 호출. `SseEmitter.send()`는 스레드 안전이지만 전송 실패(`ClientAbortException`) 시 onError → subscription.close() → 리소스 누수 방지.
3. `fromId="0"` backfill → 첫 send는 항상 `submitted`부터 시작. 클라이언트는 전체 상태 흐름을 빠짐없이 수신.
4. `final:true` 후 emitter.complete() + 60초 유예 후 `DEL stream:task:{taskId}`. 이 60초 동안 `:subscribe` 재연결 가능.

### 시나리오 D — SSE 재연결 `GET /tasks/{id}:subscribe`

```
Client → GET /agents/{name}/tasks/{taskId}:subscribe
         Header: Last-Event-ID: 1712665200000-0   (선택)
         Accept: text/event-stream

Ctrl → SubscribeTaskService
  → TaskRepo.findByIdAndUserId(taskId, userId)
     ├─ 없음 → 404 TASK_NOT_FOUND
     ├─ userId 불일치 → 403 TASK_ACCESS_DENIED
     └─ 존재 → 다음 단계

  → task.state가 터미널 && Redis Stream 아직 존재 (60s 유예 내)
     → EventBus.subscribe(taskId, fromId = Last-Event-ID ?: "0")
     → backfill로 모든 이벤트 replay → final:true 포함 → 즉시 complete
     → emitter.complete()

  → task.state가 터미널 && Redis Stream 만료 (60s 경과)
     → 410 STREAM_UNSUPPORTED
     → 클라이언트는 GET /tasks/{id} 폴링으로 전환

  → task.state가 비터미널
     → 시나리오 C와 동일: subscribe + SseEmitter (Last-Event-ID부터)
```

**정합성 포인트**:

1. `Last-Event-ID` 헤더는 SSE 표준. 브라우저 `EventSource`가 재연결 시 자동 전송. 형식이 Redis Stream entry ID(`ms-seq`)와 동일하게 설계되어 그대로 `fromStreamId`로 쓴다.
2. 만료된 스트림에 대한 재연결은 410으로 거부. MongoDB에 최종 상태는 있으므로 클라이언트가 `GET /tasks/{id}`로 전환.
3. 권한 체크는 Mongo 조회 단계에서 수행.

### 정합성 종합 테이블

| 이슈 | 해결 방식 |
|---|---|
| 레이스(Kafka publish가 subscribe보다 빠름) | `subscribe(fromId="0")` backfill → 원천 차단 |
| Kafka 재처리 멱등성 | Mongo `updateState`는 `$set`(동일 이벤트 → 동일 결과) |
| 수평 확장 인스턴스 간 브릿지 | Redis Stream이 인스턴스 간 공유 스토리지 |
| SSE 끊김 후 재연결 | `Last-Event-ID` → Redis Stream `fromId` → backfill |
| 리소스 누수 | subscription.close()를 `whenComplete`/`onTimeout`/`onCompletion`/`onError` 모든 경로에서 실행 |
| Kafka publish 실패 | 즉시 `failed` 전환 + EventBus publish(failed) + 502 응답 |
| Task 영구 이력 | MongoDB `tasks` + TTL Index 7일 |

---

## §4. 에러 처리 + 운영 고려사항

### 4.1 에러 계층

**계층 1 — Validation / 인증 (Inbound)**

| 케이스 | 포착 지점 | HTTP | 응답 |
|---|---|---|---|
| User JWT 없음/무효 | Traefik forwardAuth | 401 | (auth 파이프라인 기존 그대로) |
| 잘못된 JSON body | `HttpMessageNotReadableException` | 400 | `-32700 Parse error` |
| 필수 필드 누락 | `MethodArgumentNotValidException` | 400 | `-32602 Invalid params` |
| agentName 미존재 | (기존 처리) | 404 | `AgentNotFoundException` |

**계층 2 — 도메인 규칙 (Service throw)**

모두 `A2AException(code, message)` base 클래스. 코드가 분류.

**계층 3 — 인프라 장애 (Adapter에서 감싸 domain exception으로 재throw)**

| 장애 | 어디서 | 어떻게 감쌈 |
|---|---|---|
| Kafka send 실패/timeout | `TaskKafkaPublisher` | `KafkaPublishException` |
| MongoDB 장애 | Spring Data Mongo | `DataAccessException` → 500 + `-32603` |
| Redis 장애 | Lettuce | `RedisConnectionFailureException` → 500 + `-32603` |

**계층 4 — 프로그래머 오류 (catch-all)**

`@ExceptionHandler(Exception::class)` → 500 + `-32603 Internal error`. 본문에는 `requestId`만.

### 4.2 `A2AExceptionHandler`

```kotlin
@RestControllerAdvice
class A2AExceptionHandler {
    @ExceptionHandler(A2AException::class)
    fun handleA2A(e: A2AException): ResponseEntity<JsonRpcResponse<Nothing>> {
        val http = httpFor(e.code)
        val body = JsonRpcResponse<Nothing>(
            error = JsonRpcError(e.code, e.message ?: "A2A error"),
        )
        WideEvent.put("outcome", outcomeFor(e.code))
              .put("error_code", e.code)
              .put("error_message", e.message)
        return ResponseEntity.status(http).body(body)
    }
}
```

기존 `ApiExceptionHandler` 유지. 새 `A2AExceptionHandler`는 A2A 관련 예외만 처리. `@Order`로 우선순위 정리.

### 4.3 타임아웃 세분화

| 케이스 | 감지 | 동작 |
|---|---|---|
| Agent가 30초 내 무응답 | `CompletableFuture.orTimeout` | 504 + `-32063`. **Mongo 상태는 그대로 유지** (Agent가 나중에 응답할 수 있음). 사용자가 `GET /tasks/{id}`로 재확인 가능. |
| Kafka publish ack 타임아웃 (5초) | `TaskKafkaPublisher` | `KafkaPublishException` → Task `failed` 전환 → EventBus publish(failed) → 502 + `-32001` |
| Redis 장애로 `await` 등록 실패 | `await()` 직후 | `-32603` + 500. Mongo는 `submitted` 상태 유지. |

**핵심 규칙**: **어떤 타임아웃이든 Mongo Task를 "자동 failed 전환"하지 않는다**. Agent가 살아 있을 수 있고 결과가 나중에 올 수도 있다. A2A의 fire-and-poll 철학과 자연스럽게 맞는다.

### 4.4 Kafka ResultConsumer 에러

| 상황 | 동작 |
|---|---|
| 역직렬화 실패 | 로그 ERROR + offset commit(스킵) |
| taskId가 Mongo에 없음 | 로그 WARN + offset commit + skip |
| Mongo update 실패 | Spring Kafka `DefaultErrorHandler` 기본 10회 재시도 후 skip |
| Redis publish 실패 | Mongo update는 성공 상태 → 이벤트만 유실, 블로킹 대기자는 timeout. 로그 ERROR + offset commit |

**at-least-once**: 중복 메시지 수신 가능. Mongo update는 멱등(`$set`). Redis XADD는 중복 entry 생기지만 SSE 소비자가 entry ID로 de-dup 가능(이번 스코프 외).

### 4.5 Redis Stream Pump 스레드 패턴

"구독 하나당 XREAD BLOCK 스레드 하나"는 확장성 제약. 대신 **공유 pump 스레드 + fan-out 맵** 패턴 사용.

```
RedisStreamTaskEventBus
  activeSubs: ConcurrentHashMap<taskId, List<Listener>>
  lastIds:    ConcurrentHashMap<taskId, String>

  단일 pump 스레드:
    while (running) {
      keys = activeSubs.keys
      if (empty) wait(signal)
      ids = keys.map { lastIds[it] ?: "$" }
      entries = XREAD BLOCK 1000 STREAMS key1 key2 ... id1 id2 ...
      for each entry:
        lastIds[key] = entry.id
        dispatch entry → listeners via workerPool
    }

  구독 추가/제거 시 pump에게 signal (notify)
```

- 동시 구독 수 ∝ XREAD의 STREAMS 인자 수 (수백까지 OK)
- 스레드 풀: **pump 1개 + worker 풀(CPU 코어 수)**
- 외부 의존성 추가 없음 (Reactor 미도입)

### 4.6 리소스 한계

| 항목 | 값 | 근거 |
|---|---|---|
| Tomcat 스레드 풀 | 200 (기본) | `message:send`는 서블릿 스레드 블로킹 안 함(CompletableFuture 비동기) |
| SSE 동시 연결 목표 | 1000 / 인스턴스 | 실측 후 조정 |
| SSE `emitter-timeout` | 30분 | 장시간 태스크 대응 |
| SSE 하트비트 | 15초 | Cloudflare/Traefik idle disconnect 방지 |
| Kafka publish ack 대기 | 5초 | 과도한 블로킹 방지 |
| 블로킹 `message:send` 타임아웃 | 30초 | Cloudflare 60초 이내 마진 |
| Redis Stream 유예 TTL | 60초 | 모바일 재연결 여유 |
| MongoDB TTL Index 주기 | 60초 (기본) | 7일 task 자동 삭제 |

### 4.7 로깅 (WideEvent)

| 엔드포인트 | 주요 필드 | outcome 값 |
|---|---|---|
| `POST message:send` (block) | `task_id, agent_id, agent_name, user_id, request_id, context_id, return_immediately, latency_ms, terminal_state` | `task_completed`, `task_failed`, `agent_unavailable`, `agent_timeout`, `kafka_publish_failed` |
| `POST message:send` (async) | 위 + `return_immediately=true` | `task_submitted`, `agent_unavailable`, `kafka_publish_failed` |
| `POST message:stream` | 위 + `sse_event_count, stream_duration_ms, disconnect_reason` | `stream_completed`, `stream_client_disconnect`, `stream_server_timeout`, `agent_unavailable` |
| `GET tasks/{id}` | `task_id, user_id, current_state` | `task_retrieved`, `task_not_found`, `task_access_denied` |
| `GET tasks/{id}:subscribe` | 위 + `last_event_id, backfill_count` | `stream_resubscribed`, `stream_expired`, `task_not_found`, `task_access_denied` |
| `ResultConsumer.onMessage` (배경) | `task_id, agent_id, state, kafka_offset, kafka_partition` | `result_processed`, `result_skipped_unknown`, `result_skipped_terminal`, `mongo_update_failed`, `redis_publish_failed` |

**문서 업데이트**:

- `docs/guides/logging/flows/api-agent-registry.md` — `message:stream`, `GET /tasks/{id}`, `:subscribe` 섹션 추가
- `docs/guides/logging/flows/api-task-processing.md` — **신규** — 태스크 처리 흐름 전담

### 4.8 관측성

이번 스코프에서는 로그만. Loki/Grafana에서 로그 필드로 집계. Micrometer metric은 다음 이터레이션.

핵심 타이밍 필드:
- `latency_ms` (block `message:send`)
- `agent_response_latency_ms` (publish 시점 ~ result 수신 시점)
- `active_sse_connections` (주기적 snapshot 로그, 30초 간격)

---

## §5. 프로젝트 구조 + 테스트 전략

### 5.1 신규/수정 파일 트리

```
apps/api/src/main/kotlin/com/bara/api/
├── domain/
│   ├── model/
│   │   ├── Task.kt                     ★ 신규
│   │   ├── TaskId.kt                   ★ 신규
│   │   ├── TaskState.kt                ★ 신규
│   │   ├── A2AMessage.kt               ★ 신규
│   │   ├── A2AArtifact.kt              ★ 신규
│   │   └── TaskEvent.kt                ★ 신규
│   └── exception/
│       ├── A2AException.kt             ★ 신규 (base)
│       ├── AgentUnavailableException.kt (기존, 코드 부여)
│       ├── AgentTimeoutException.kt    ★ 신규
│       ├── TaskNotFoundException.kt    ★ 신규
│       ├── TaskAccessDeniedException.kt ★ 신규
│       ├── StreamUnsupportedException.kt ★ 신규
│       └── KafkaPublishException.kt    ★ 신규
│
├── application/
│   ├── port/
│   │   ├── in/
│   │   │   ├── command/
│   │   │   │   └── SendMessageUseCase.kt (기존 수정)
│   │   │   └── query/
│   │   │       ├── StreamMessageUseCase.kt ★ 신규
│   │   │       ├── GetTaskUseCase.kt       ★ 신규
│   │   │       └── SubscribeTaskUseCase.kt ★ 신규
│   │   └── out/
│   │       ├── TaskPublisherPort.kt    (기존 수정 — typed payload + ack)
│   │       ├── TaskRepositoryPort.kt   ★ 신규
│   │       └── TaskEventBusPort.kt     ★ 신규 (+ Subscription)
│   └── service/
│       ├── command/
│       │   └── SendMessageService.kt   (기존 수정 — await/async 분기)
│       └── query/
│           ├── StreamMessageService.kt ★ 신규
│           ├── GetTaskService.kt       ★ 신규
│           └── SubscribeTaskService.kt ★ 신규
│
├── adapter/
│   ├── in/
│   │   ├── rest/
│   │   │   ├── AgentController.kt      (기존 수정)
│   │   │   ├── AgentDtos.kt            (기존 수정)
│   │   │   ├── a2a/
│   │   │   │   ├── JsonRpcRequest.kt   ★ 신규
│   │   │   │   ├── JsonRpcResponse.kt  ★ 신규
│   │   │   │   ├── JsonRpcError.kt     ★ 신규
│   │   │   │   ├── A2ATaskDto.kt       ★ 신규
│   │   │   │   ├── A2ATaskStatusDto.kt ★ 신규
│   │   │   │   ├── A2AMessageDto.kt    ★ 신규
│   │   │   │   ├── A2AArtifactDto.kt   ★ 신규
│   │   │   │   ├── SendMessageRequestDto.kt ★ 신규
│   │   │   │   └── A2AErrorCode.kt     ★ 신규
│   │   │   ├── mapper/
│   │   │   │   └── A2ATaskMapper.kt    ★ 신규
│   │   │   └── A2AExceptionHandler.kt  ★ 신규
│   │   └── kafka/
│   │       └── ResultConsumerAdapter.kt ★ 신규
│   └── out/
│       ├── persistence/
│       │   ├── TaskDocument.kt         ★ 신규
│       │   ├── TaskRepository.kt       ★ 신규 (Spring Data interface)
│       │   ├── TaskMongoAdapter.kt     ★ 신규
│       │   └── TaskTtlIndexInitializer.kt ★ 신규
│       ├── kafka/
│       │   ├── TaskKafkaPublisher.kt   (기존 수정 — ack 대기)
│       │   └── TaskMessagePayload.kt   ★ 신규
│       └── redis/
│           ├── RedisStreamTaskEventBus.kt ★ 신규
│           ├── EventBusPumpThread.kt   ★ 신규
│           └── TaskEventJson.kt        ★ 신규
│
└── config/
    ├── KafkaConfig.kt                  (기존 수정)
    ├── RedisConfig.kt                  (기존 수정)
    └── SseConfig.kt                    ★ 신규
```

**정리 원칙**:
- `Task` 도메인은 `Agent` 도메인과 분리된 별도 aggregate.
- Port/Adapter 분리 엄격. `TaskEventBusPort`는 Redis Stream에 종속되지 않음 — 테스트는 인메모리 fake로 가능.
- A2A wire DTO(`adapter/in/rest/a2a/`)와 도메인 모델(`domain/model/`) 완전 분리. 매퍼가 경계 담당.

### 5.2 기존 파일 수정 요약

| 파일 | 변경 | 호환성 |
|---|---|---|
| `SendMessageService.kt` | `String(taskId)` 반환 → `CompletableFuture<A2ATaskDto>` (블로킹) / 즉시 `A2ATaskDto` (async) 분기 | breaking — 테스트 수정 필요 |
| `AgentController.sendMessage()` | 반환 타입 변경, SSE/폴링/재연결 엔드포인트 추가 | breaking — 컨트롤러 테스트 수정 |
| `AgentDtos.SendMessageApi*` | A2A JSON-RPC 구조로 재설계 | breaking — 외부 클라이언트 없으므로 허용 |
| `TaskKafkaPublisher` | `void` → `SendResult` 반환, typed payload | breaking — 호출자만 영향 |
| `TaskPublisherPort` | 시그니처 변경 | 위와 동일 |

**클라이언트 영향 없음**: 현재 `/api/core/agents/{agentName}/message:send`를 호출하는 실제 클라이언트는 없음(FE 미연결).

### 5.3 의존성

**새 외부 의존성 없음**. 기존 스택으로 완결:

- `spring-boot-starter-data-mongodb` (이미 있음)
- `spring-boot-starter-data-redis` (이미 있음, Lettuce Stream 명령 포함)
- `spring-kafka` (이미 있음)
- `spring-boot-starter-web` (이미 있음, `SseEmitter` 내장)
- JDK `CompletableFuture` (표준)

### 5.4 설정 (`application.yml`)

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:...}
    producer:
      acks: all
      delivery-timeout-ms: 5000
      retries: 3
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer
    consumer:
      group-id: api-service-results        # shared
      enable-auto-commit: false
      auto-offset-reset: latest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
    listener:
      ack-mode: manual

bara:
  api:
    task:
      block-timeout-seconds: 30
      kafka-publish-timeout-seconds: 5
      stream-grace-period-seconds: 60
      mongo-ttl-days: 7
    sse:
      emitter-timeout-ms: 1800000           # 30분
      heartbeat-interval-ms: 15000          # 15초
      max-concurrent-connections: 1000
```

모두 `@ConfigurationProperties(prefix="bara.api.task")` / `bara.api.sse`로 묶어 타입 안전성 확보. 환경변수 override 가능 (`BARA_API_TASK_BLOCK_TIMEOUT_SECONDS` 등).

### 5.5 테스트 전략

**레이어별**:

```
L1: Domain 단위 테스트 (빠름, 순수)
  - Task 상태 전환, TaskState.isTerminal
  - A2ATaskMapper (Domain ↔ DTO)

L2: Application Service 단위 테스트 (빠름, Port mock)
  - SendMessageService 블로킹/async 분기 + 에러 경로
  - StreamMessageService subscribe + emitter 연결
  - GetTaskService / SubscribeTaskService 권한 체크 및 만료 처리
  - ResultConsumerAdapter onMessage 분기

L3: Adapter 슬라이스 테스트
  - @WebMvcTest AgentController (MockMvc + SSE asyncDispatch)
  - @DataMongoTest TaskMongoAdapter
  - @DataRedisTest RedisStreamTaskEventBus (pump 스레드 + backfill)
  - @EmbeddedKafka TaskKafkaPublisher / ResultConsumerAdapter

L4: 통합 시나리오 테스트
  - 각 시나리오 end-to-end
```

**L4 통합 케이스 목록**:

1. 블로킹 send → completed 수신 → 200 + A2A Task
2. 블로킹 send → failed 수신 → 200 + A2A Task (state:failed)
3. 블로킹 send → 타임아웃 → 504 + AGENT_TIMEOUT
4. 블로킹 send → Registry 미등록 → 503 + AGENT_UNAVAILABLE
5. 블로킹 send → Kafka publish 실패 → 502 + KAFKA_PUBLISH_FAILED
6. async send → 즉시 submitted 응답
7. async send → 폴링으로 completed 확인
8. GET /tasks/{id} → 다른 user → 403
9. GET /tasks/{id} → 존재하지 않음 → 404
10. SSE stream → submitted → working → completed 3개 이벤트 수신 후 종료
11. SSE stream → 클라이언트 연결 끊김 → subscription 정리
12. :subscribe 재연결 → Last-Event-ID 이후 이벤트만 수신
13. :subscribe 재연결 → 만료된 스트림 → 410 STREAM_UNSUPPORTED
14. 동시 SSE 구독 100개 → 모두 정상 이벤트 수신 (pump 스레드 검증)
15. ResultConsumer 중복 메시지 → Mongo 상태 멱등
16. Agent의 `canceled` 상태가 A2A로 들어오면 수용 (수신 처리만 보장)

**테스트 파일 배치**:

```
apps/api/src/test/kotlin/com/bara/api/
├── domain/TaskTest.kt, TaskStateTest.kt
├── application/service/
│   ├── SendMessageServiceTest.kt
│   ├── StreamMessageServiceTest.kt
│   ├── GetTaskServiceTest.kt
│   └── SubscribeTaskServiceTest.kt
├── adapter/
│   ├── in/rest/
│   │   ├── AgentControllerTest.kt          (기존 수정)
│   │   ├── AgentControllerStreamTest.kt    ★ 신규
│   │   ├── AgentControllerGetTaskTest.kt   ★ 신규
│   │   ├── A2AExceptionHandlerTest.kt      ★ 신규
│   │   └── mapper/A2ATaskMapperTest.kt     ★ 신규
│   ├── in/kafka/
│   │   └── ResultConsumerAdapterTest.kt    ★ 신규
│   └── out/
│       ├── persistence/TaskMongoAdapterTest.kt  ★ 신규
│       ├── kafka/TaskKafkaPublisherTest.kt      (기존 수정)
│       └── redis/RedisStreamTaskEventBusTest.kt ★ 신규
└── integration/
    └── TaskProcessingIntegrationTest.kt    ★ 신규
```

**MongoDB/Redis 테스트 방식**: writing-plans 단계에서 기존 프로젝트의 관행 확인 후 일관되게 선택 (Testcontainers vs Flapdoodle).

### 5.6 문서 업데이트

| 문서 | 변경 |
|---|---|
| `docs/spec/api/agent-registry.md` | 태스크 엔드포인트 섹션 "설계됨 → 구현됨" 갱신 |
| `docs/spec/shared/messaging.md` | 응답 방식 섹션 블로킹 `message:send` 명시 보강 |
| `docs/superpowers/specs/2026-04-09-agent-registry-redesign-design.md` | A2A 프록시 엔드포인트 섹션 "결과 대기 미구현" 상호 참조로 업데이트 |
| `docs/guides/logging/flows/api-agent-registry.md` | `message:stream`, `GET /tasks/{id}`, `:subscribe` 로깅 필드 |
| `docs/guides/logging/flows/api-task-processing.md` | ★ 신규 전담 가이드 |
| `CLAUDE.md` | API Service "태스크 처리(동기+SSE) ○" → "✓" (구현 완료 시) |

---

## §6. 스코프 체크리스트 (Definition of Done)

**엔드포인트**:

- [x] `POST /agents/{agentName}/message:send` — 블로킹 동기 + `returnImmediately=true` 비동기
- [x] `POST /agents/{agentName}/message:stream` — SSE 스트리밍
- [x] `GET /agents/{agentName}/tasks/{taskId}` — 폴링 조회
- [x] `GET /agents/{agentName}/tasks/{taskId}:subscribe` — SSE 재연결
- [ ] `POST /agents/{agentName}/tasks/{taskId}:cancel` — **스코프 외**

**인프라**:

- [x] MongoDB `tasks` 컬렉션 + TTL Index
- [x] Redis Stream 이벤트 버스 (pump 스레드 + fan-out)
- [x] Kafka `results.api` ResultConsumer (shared group, 수동 commit)
- [x] SSE heartbeat 스케줄러

**에러 / 운영**:

- [x] A2A JSON-RPC envelope + `A2AExceptionHandler`
- [x] Kafka publish ack 대기 + 실패 시 `failed` 전환
- [x] 타임아웃 시 Mongo 상태 유지 (자동 failed 전환 없음)
- [x] WideEvent 로깅 + `docs/guides/logging/flows/` 업데이트

**테스트**:

- [x] L1~L4 전 레이어 (통합 케이스 16개)
- [x] 동시 SSE 100 연결 스트레스 테스트

**스코프 외 (명시적 제외)**:

- `:cancel` 엔드포인트 / CancelTaskService
- `input-required`, `auth-required` 상태 처리
- 멀티턴 히스토리 (`historyLength`)
- Kafka DLQ
- Micrometer metric / Grafana 대시보드
- A2A `pushNotificationConfig`
- Redis Stream 이벤트 de-dup

---

## 부록: 참고 문서

- [A2A Protocol Specification](https://a2a-protocol.org/latest/specification/)
- [A2A Sample Methods and JSON Responses](https://a2aprotocol.ai/docs/guide/a2a-sample-methods-and-json-responses)
- [`docs/spec/api/agent-registry.md`](../../spec/api/agent-registry.md)
- [`docs/spec/shared/messaging.md`](../../spec/shared/messaging.md)
- [`docs/superpowers/specs/2026-04-09-agent-registry-redesign-design.md`](2026-04-09-agent-registry-redesign-design.md)
- [`docs/superpowers/specs/2026-04-08-agent-kafka-transport-design.md`](2026-04-08-agent-kafka-transport-design.md)
- [`docs/spec/decisions/adr-001-a2a-protocol.md`](../../spec/decisions/adr-001-a2a-protocol.md)

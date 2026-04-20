# API Task Processing Phase 2 (비동기 + 폴링 + JSON-RPC) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /agents/{name}/message:send` 에 `returnImmediately=true` 비동기 모드를 추가하고, `GET /agents/{name}/tasks/{taskId}` 폴링 엔드포인트를 추가하며, A2A 응답을 전부 JSON-RPC 2.0 envelope으로 정리한다.

**Architecture:** Phase 1 이 블로킹 동기 플로우를 구축했기 때문에 Phase 2 는 **분기/재사용** 이 본질이다. `SendMessageService` 에 `sendAsync` 를 추가해 `await` 을 거치지 않고 즉시 `submitted` Task 를 반환시키고, `GetTaskService` 는 `TaskRepositoryPort.findById` 로 Mongo 만 조회한다. A2A 관련 예외는 `A2AException` base class 로 통일하고, 신규 `A2AExceptionHandler` 가 이들을 JSON-RPC error envelope 으로 변환한다. 컨트롤러는 request/response 양쪽 모두 `JsonRpcRequest`/`JsonRpcResponse` envelope 을 사용한다. Phase 3 (SSE) 는 별도로 남긴다.

**Tech Stack:** Kotlin 1.9 / Spring Boot 3 / Spring MVC (`CompletableFuture` 비동기) / Jackson (`JsonNode` for raw `id` pass-through) / MongoDB (영구 Task 이력) / Redis Stream (인스턴스 간 이벤트 버스, Phase 1 그대로 유지) / Kafka (에이전트 발행 채널, Phase 1 그대로 유지). 테스트: JUnit 5 + mockk + Spring WebMvcTest + Testcontainers e2e.

---

## Phase 2 전제

- Phase 1 merge commit (`develop 79205ac feat(api): Phase 1 blocking A2A task processing`) 을 base 로 한다. `SendMessageService.sendBlocking`, `ResultConsumerAdapter`, `RedisStreamTaskEventBus`, `TaskMongoRepository`, `TaskProperties`, `AgentController#sendMessage` 등이 이미 존재한다.
- 본 Phase 는 spec `docs/superpowers/specs/2026-04-09-task-processing-design.md` 의 **시나리오 B (비동기 + 폴링)** 및 **§2.6 JSON-RPC DTO**, **§2.7 에러 코드 테이블**, **§4.1-4.2 에러 계층** 을 구현한다.
- SSE (`message:stream`, `:subscribe`) 는 **본 Phase 에서 다루지 않는다** — 시나리오 C/D 는 Phase 3 로 연기한다. Redis Stream pump thread 패턴, `Last-Event-ID` 재연결, `SseEmitter` 관련 컴포넌트는 전부 제외.
- 기존 블로킹 엔드포인트는 하위 호환을 **깨고** JSON-RPC envelope 으로 마이그레이션한다. 현재 FE (`apps/fe/src/pages/AgentsPage.tsx`) 는 CRUD 만 호출하고 `message:send` 를 호출하는 코드가 없으므로 FE 수정은 **본 Phase 범위 밖**이다. FE 에서 채팅 UI 가 추가될 때 envelope 포맷으로 구현한다. 외부 에이전트 클라이언트도 아직 없으므로 breaking change 가 허용된다.
- JSON-RPC `id` 필드는 **요청→응답 echo** 하되, **에러 응답에는 항상 `null`** 로 둔다. Phase 3 에서 ThreadLocal-based capture 를 고려한다 (본 Phase 범위 외).

---

## Phase 2 파일 트리 (신규/수정 요약)

### 신규 파일

```
apps/api/src/main/kotlin/com/bara/api/
├── domain/exception/
│   ├── A2AException.kt                ← base class + A2AErrorCodes
│   ├── TaskNotFoundException.kt       ← -32064
│   └── TaskAccessDeniedException.kt   ← -32065
├── adapter/in/rest/
│   ├── A2AExceptionHandler.kt         ← @RestControllerAdvice, JSON-RPC envelope
│   └── a2a/
│       └── JsonRpcDtos.kt             ← JsonRpcRequest/Response/Error
├── application/
│   ├── port/in/query/GetTaskQuery.kt  ← GET /tasks/{id} UseCase port
│   └── service/query/GetTaskService.kt

apps/api/src/test/kotlin/com/bara/api/
├── adapter/in/rest/
│   └── A2AExceptionHandlerTest.kt     ← WebMvcTest
└── application/service/query/
    └── GetTaskServiceTest.kt          ← mockk unit test

docs/
└── guides/logging/flows/
    └── api-task-processing.md          ← Phase 2 엔드포인트 로깅 필드 추가 (수정)
```

### 수정 파일

```
apps/api/src/main/kotlin/com/bara/api/
├── domain/exception/
│   ├── AgentUnavailableException.kt   ← extends A2AException
│   ├── AgentTimeoutException.kt       ← extends A2AException
│   └── KafkaPublishException.kt       ← extends A2AException
├── adapter/in/rest/
│   ├── AgentController.kt             ← sendMessage envelope + getTask 엔드포인트
│   ├── AgentDtos.kt                    ← SendMessageApiRequest 제거, SendMessageParams/ConfigurationRequest 추가
│   └── ApiExceptionHandler.kt         ← A2A 관련 handler 3개 제거 (A2AExceptionHandler 로 이관)
├── application/
│   ├── port/in/command/SendMessageUseCase.kt  ← sendAsync 추가
│   └── service/command/SendMessageService.kt  ← sendAsync 구현

apps/api/src/test/kotlin/com/bara/api/
├── adapter/in/rest/AgentControllerTest.kt   ← envelope + getTask + returnImmediately 테스트
└── application/service/command/SendMessageServiceTest.kt ← sendAsync 테스트 추가

apps/api/src/e2eTest/kotlin/com/bara/api/e2e/scenario/
└── TaskProcessingScenarioTest.kt    ← 기존 1-4 envelope 으로 마이그레이션, 6-9 신규
```

### FE 파일 변경 없음

`apps/fe/src/pages/AgentsPage.tsx` 는 현재 `message:send` 를 호출하지 않는다 (CRUD 만). 본 Phase 에서는 FE 변경 없음. FE 에 채팅 UI 가 추가될 때 envelope 포맷으로 구현.

### 삭제 없음

기존 코드는 전부 수정만 한다. 단 `SendMessageApiRequest` 는 `SendMessageParams` 로 **이름 변경 + 필드 재구성** 이므로 사실상 교체.

---

## 의존/호출 그래프 (Phase 2 관점)

```
HTTP POST /agents/{name}/message:send
     │
     ▼
AgentController.sendMessage(envelope: JsonRpcRequest<SendMessageParams>)
     │
     ├── params.configuration?.returnImmediately == true
     │     │
     │     ▼
     │   sendMessageUseCase.sendAsync(userId, agentName, request) : A2ATaskDto
     │     │    (AgentRegistryPort → TaskRepositoryPort.save → TaskEventBusPort.publish
     │     │     → TaskPublisherPort.publish(Kafka ack) → A2ATaskMapper.toDto)
     │     ▼
     │   CompletableFuture.completedFuture(JsonRpcResponse.result(dto))
     │
     └── returnImmediately == false
           ▼
         sendMessageUseCase.sendBlocking(...)  ← Phase 1 과 동일
           .thenApply { JsonRpcResponse.result(it) }

HTTP GET /agents/{name}/tasks/{taskId}
     │
     ▼
AgentController.getTask(userId, agentName, taskId)
     │
     ▼
getTaskQuery.getTask(userId, taskId) : A2ATaskDto
     │    1. taskRepositoryPort.findById(taskId) ?: throw TaskNotFoundException
     │    2. task.userId != userId → throw TaskAccessDeniedException
     │    3. A2ATaskMapper.toDto(task)
     ▼
ResponseEntity.ok(JsonRpcResponse(id=null, result=dto))

---- 예외 경로 ----

모든 A2AException (Agent*, Task*, KafkaPublish*) →
     A2AExceptionHandler.handleA2A → ResponseEntity
         .status(httpFor(code))
         .body(JsonRpcResponse(error = JsonRpcError(code, message)))

기존 CRUD 예외 (AgentNotFound, AgentNameAlreadyExists, AgentOwnership, AgentNotRegistered) →
     ApiExceptionHandler (변경 없음) — 기존 REST 포맷 ErrorResponse 유지
```

---

## Task 1: A2AException base class + Task exceptions

**Files:**

- Create: `apps/api/src/main/kotlin/com/bara/api/domain/exception/A2AException.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/domain/exception/TaskNotFoundException.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/domain/exception/TaskAccessDeniedException.kt`

**배경**: spec §2.7 에서 A2A 에러 코드를 JSON-RPC Server Error 범위(-32000~-32099)에 정의한다. base class 에 `code: Int` 필드를 둬서 `A2AExceptionHandler` 가 하나의 handler 로 모든 서브클래스를 처리하도록 만든다. `HttpStatus` 는 domain 이 웹 계층을 의존하지 않도록 handler 쪽에서 매핑한다.

- [ ] **Step 1: A2AException base class 작성**

```kotlin
// apps/api/src/main/kotlin/com/bara/api/domain/exception/A2AException.kt
package com.bara.api.domain.exception

/**
 * A2A 프로토콜의 JSON-RPC 에러로 변환되는 도메인 예외의 베이스.
 * 각 서브클래스는 [A2AErrorCodes] 의 상수를 그대로 `code` 로 전달한다.
 * HTTP 매핑은 [com.bara.api.adapter.`in`.rest.A2AExceptionHandler] 가 담당한다.
 */
open class A2AException(
    val code: Int,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * JSON-RPC Server Error 범위(-32000~-32099)에서 내부적으로 정의한 A2A 에러 코드.
 * spec §2.7 참조.
 */
object A2AErrorCodes {
    const val KAFKA_PUBLISH_FAILED = -32001
    const val AGENT_UNAVAILABLE = -32062
    const val AGENT_TIMEOUT = -32063
    const val TASK_NOT_FOUND = -32064
    const val TASK_ACCESS_DENIED = -32065
}
```

- [ ] **Step 2: TaskNotFoundException 작성**

```kotlin
// apps/api/src/main/kotlin/com/bara/api/domain/exception/TaskNotFoundException.kt
package com.bara.api.domain.exception

class TaskNotFoundException(taskId: String) : A2AException(
    code = A2AErrorCodes.TASK_NOT_FOUND,
    message = "Task not found: $taskId",
)
```

- [ ] **Step 3: TaskAccessDeniedException 작성**

```kotlin
// apps/api/src/main/kotlin/com/bara/api/domain/exception/TaskAccessDeniedException.kt
package com.bara.api.domain.exception

class TaskAccessDeniedException(taskId: String) : A2AException(
    code = A2AErrorCodes.TASK_ACCESS_DENIED,
    message = "Task access denied: $taskId",
)
```

- [ ] **Step 4: 컴파일 확인**

Run: `./gradlew :apps:api:compileKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: commit**

```bash
git add apps/api/src/main/kotlin/com/bara/api/domain/exception/A2AException.kt \
        apps/api/src/main/kotlin/com/bara/api/domain/exception/TaskNotFoundException.kt \
        apps/api/src/main/kotlin/com/bara/api/domain/exception/TaskAccessDeniedException.kt
git commit -m "feat(api): add A2AException base class with JSON-RPC error codes"
```

---

## Task 2: 기존 A2A 예외 3개를 A2AException 서브클래스로 리팩터

**Files:**

- Modify: `apps/api/src/main/kotlin/com/bara/api/domain/exception/AgentUnavailableException.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/domain/exception/AgentTimeoutException.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/domain/exception/KafkaPublishException.kt`

**배경**: 이 세 예외는 전부 A2A 흐름(`message:send`, `tasks/{id}`) 에서만 throw 된다 (grep 으로 확인됨). `A2AException` 을 상속시키면 `A2AExceptionHandler` 하나로 처리가 끝나고, `ApiExceptionHandler` 에서 각각의 핸들러를 제거할 수 있다. 나머지 `AgentNotFoundException`, `AgentNameAlreadyExistsException`, `AgentOwnershipException`, `AgentNotRegisteredException` 은 CRUD (`POST /agents`, `POST /agents/{name}/registry` 등) 에서만 사용되므로 **손대지 않고 `ApiExceptionHandler` 에 그대로 둔다**.

- [ ] **Step 1: AgentUnavailableException 을 A2AException 상속으로 교체**

```kotlin
// apps/api/src/main/kotlin/com/bara/api/domain/exception/AgentUnavailableException.kt
package com.bara.api.domain.exception

class AgentUnavailableException : A2AException(
    code = A2AErrorCodes.AGENT_UNAVAILABLE,
    message = "Agent is not available",
)
```

- [ ] **Step 2: AgentTimeoutException 을 A2AException 상속으로 교체**

```kotlin
// apps/api/src/main/kotlin/com/bara/api/domain/exception/AgentTimeoutException.kt
package com.bara.api.domain.exception

class AgentTimeoutException(
    message: String = "Agent did not respond within timeout",
) : A2AException(
    code = A2AErrorCodes.AGENT_TIMEOUT,
    message = message,
)
```

- [ ] **Step 3: KafkaPublishException 을 A2AException 상속으로 교체**

현재 `KafkaPublishException.kt` 내용 확인 후 `cause` 필드가 있다면 base 가 지원하도록 유지. `A2AException(code, message, cause)` 시그니처에 이미 cause 가 있으므로 그대로 전달.

```kotlin
// apps/api/src/main/kotlin/com/bara/api/domain/exception/KafkaPublishException.kt
package com.bara.api.domain.exception

class KafkaPublishException(
    message: String,
    cause: Throwable? = null,
) : A2AException(
    code = A2AErrorCodes.KAFKA_PUBLISH_FAILED,
    message = message,
    cause = cause,
)
```

- [ ] **Step 4: 전체 Kotlin 컴파일 확인**

Run: `./gradlew :apps:api:compileKotlin`
Expected: `BUILD SUCCESSFUL`

이 시점에서 `ApiExceptionHandler` 의 `handleAgentUnavailable` / `handleAgentTimeout` / `handleKafkaPublish` 는 여전히 남아있지만 A2AExceptionHandler 가 아직 없으므로 **아직 제거하지 않는다**. Task 4 에서 한 번에 정리.

- [ ] **Step 5: 단위 테스트 스위트가 깨지는지 확인**

Run: `./gradlew :apps:api:test --tests 'com.bara.api.application.service.command.SendMessageServiceTest' --tests 'com.bara.api.adapter.out.kafka.TaskKafkaPublisherTest'`
Expected: 모두 PASS. (기존 테스트는 예외의 메시지와 `is` 타입만 확인하고 code 는 확인하지 않으므로 그대로 통과해야 한다.)

- [ ] **Step 6: commit**

```bash
git add apps/api/src/main/kotlin/com/bara/api/domain/exception/AgentUnavailableException.kt \
        apps/api/src/main/kotlin/com/bara/api/domain/exception/AgentTimeoutException.kt \
        apps/api/src/main/kotlin/com/bara/api/domain/exception/KafkaPublishException.kt
git commit -m "refactor(api): migrate Agent/Kafka exceptions to A2AException base"
```

---

## Task 3: JsonRpcDtos 작성 (JsonRpcRequest / JsonRpcResponse / JsonRpcError)

**Files:**

- Create: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/a2a/JsonRpcDtos.kt`

**배경**: spec §2.6 의 wire 포맷을 그대로 구현한다. 세 가지 주의점:

1. `id` 필드는 JSON-RPC 2.0 스펙에서 string/number/null 이 모두 유효하다. Kotlin 에서 이를 자연스럽게 받으려면 `com.fasterxml.jackson.databind.JsonNode?` 가 가장 안전하다 — Jackson 이 deserialization 시 타입을 고정하지 않고 raw 노드로 받아 쓰기는 그대로 직렬화된다.
2. `JsonRpcResponse` 는 `result` 와 `error` 가 상호 배타적이다. 둘 다 null 이거나 어느 한쪽만 non-null. Jackson `@JsonInclude(NON_NULL)` 을 달아 null 필드는 응답에서 생략되게 한다.
3. 본 파일 하나에 세 DTO 를 몰아 넣는다. Phase 1 의 `A2ATaskDto.kt` 과 동일한 컨벤션.

- [ ] **Step 1: JsonRpcDtos.kt 작성**

```kotlin
// apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/a2a/JsonRpcDtos.kt
package com.bara.api.adapter.`in`.rest.a2a

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode

/**
 * JSON-RPC 2.0 request envelope.
 * A2A `message/send` 같은 POST 엔드포인트는 본 DTO 로 body 를 수신한다.
 *
 * `id` 는 string/number/null 을 모두 수용해야 하므로 raw [JsonNode] 로 보관한다.
 * 응답에서 동일한 id 를 echo back 할 때도 JsonNode 를 그대로 써서 타입 정보를 보존한다.
 */
data class JsonRpcRequest<T>(
    val jsonrpc: String = "2.0",
    val id: JsonNode? = null,
    val method: String? = null,
    val params: T? = null,
)

/**
 * JSON-RPC 2.0 response envelope. `result` 와 `error` 는 상호 배타적이다.
 * null 필드는 wire 에서 생략되도록 `@JsonInclude(NON_NULL)` 적용.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
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
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :apps:api:compileKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: commit**

```bash
git add apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/a2a/JsonRpcDtos.kt
git commit -m "feat(api): add JSON-RPC 2.0 envelope DTOs for A2A wire format"
```

---

## Task 4: A2AExceptionHandler + ApiExceptionHandler 정리

**Files:**

- Create: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/A2AExceptionHandler.kt`
- Create: `apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/A2AExceptionHandlerTest.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/ApiExceptionHandler.kt`

**배경**: `@ExceptionHandler(A2AException::class)` 하나로 모든 A2A 예외를 잡아 JSON-RPC envelope 로 변환한다. `A2AErrorCodes` → `HttpStatus` / `outcome` 매핑은 handler 내부의 private 함수로 표현한다. `ApiExceptionHandler` 에서는 `handleAgentUnavailable` / `handleAgentTimeout` / `handleKafkaPublish` 세 개를 **제거**한다 (이들 예외는 이제 `A2AException` 서브클래스라서 `A2AExceptionHandler` 가 우선적으로 잡는다; handler 가 중복되면 Spring 이 "ambiguous" 예외를 던지므로 반드시 제거).

**주의**: 두 handler 에 동시에 동일 타입을 등록하면 런타임 ambiguous 에러가 나므로, ApiExceptionHandler 정리와 A2AExceptionHandler 추가를 같은 commit 에서 수행한다.

- [ ] **Step 1: 실패 테스트 작성 — A2AExceptionHandlerTest**

`A2AExceptionHandler` 는 `@RestControllerAdvice` 이므로 WebMvcTest 로 컨트롤러 + 내 advice 를 로드해 실제 응답 포맷을 검증한다. 별도 테스트용 더미 컨트롤러를 한 파일에 `@TestConfiguration` 안에 선언하면 실제 프로덕션 컨트롤러와 독립적으로 테스트할 수 있다.

```kotlin
// apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/A2AExceptionHandlerTest.kt
package com.bara.api.adapter.`in`.rest

import com.bara.api.domain.exception.A2AErrorCodes
import com.bara.api.domain.exception.A2AException
import com.bara.api.domain.exception.AgentTimeoutException
import com.bara.api.domain.exception.AgentUnavailableException
import com.bara.api.domain.exception.KafkaPublishException
import com.bara.api.domain.exception.TaskAccessDeniedException
import com.bara.api.domain.exception.TaskNotFoundException
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.stereotype.Controller
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseBody

@Controller
@RequestMapping("/__test/a2a")
class A2AExceptionTestController {
    @GetMapping("/throw/{code}")
    @ResponseBody
    fun throwByCode(@PathVariable code: Int): Nothing = when (code) {
        A2AErrorCodes.AGENT_UNAVAILABLE -> throw AgentUnavailableException()
        A2AErrorCodes.AGENT_TIMEOUT -> throw AgentTimeoutException("simulated 30s timeout")
        A2AErrorCodes.KAFKA_PUBLISH_FAILED -> throw KafkaPublishException("broker down")
        A2AErrorCodes.TASK_NOT_FOUND -> throw TaskNotFoundException("task-missing")
        A2AErrorCodes.TASK_ACCESS_DENIED -> throw TaskAccessDeniedException("task-other")
        else -> throw A2AException(code = code, message = "unknown")
    }
}

@WebMvcTest(controllers = [A2AExceptionTestController::class])
@Import(A2AExceptionHandler::class)
@TestPropertySource(
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration," +
            "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
    ],
)
class A2AExceptionHandlerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `AgentUnavailableException → 503 + code=-32062`() {
        mockMvc.get("/__test/a2a/throw/${A2AErrorCodes.AGENT_UNAVAILABLE}").andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.jsonrpc") { value("2.0") }
            jsonPath("$.error.code") { value(A2AErrorCodes.AGENT_UNAVAILABLE) }
            jsonPath("$.error.message") { value("Agent is not available") }
            jsonPath("$.result") { doesNotExist() }
            jsonPath("$.id") { doesNotExist() }
        }
    }

    @Test
    fun `AgentTimeoutException → 504 + code=-32063`() {
        mockMvc.get("/__test/a2a/throw/${A2AErrorCodes.AGENT_TIMEOUT}").andExpect {
            status { isGatewayTimeout() }
            jsonPath("$.error.code") { value(A2AErrorCodes.AGENT_TIMEOUT) }
            jsonPath("$.error.message") { value("simulated 30s timeout") }
        }
    }

    @Test
    fun `KafkaPublishException → 502 + code=-32001`() {
        mockMvc.get("/__test/a2a/throw/${A2AErrorCodes.KAFKA_PUBLISH_FAILED}").andExpect {
            status { isBadGateway() }
            jsonPath("$.error.code") { value(A2AErrorCodes.KAFKA_PUBLISH_FAILED) }
            jsonPath("$.error.message") { value("broker down") }
        }
    }

    @Test
    fun `TaskNotFoundException → 404 + code=-32064`() {
        mockMvc.get("/__test/a2a/throw/${A2AErrorCodes.TASK_NOT_FOUND}").andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value(A2AErrorCodes.TASK_NOT_FOUND) }
            jsonPath("$.error.message") { value("Task not found: task-missing") }
        }
    }

    @Test
    fun `TaskAccessDeniedException → 403 + code=-32065`() {
        mockMvc.get("/__test/a2a/throw/${A2AErrorCodes.TASK_ACCESS_DENIED}").andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value(A2AErrorCodes.TASK_ACCESS_DENIED) }
            jsonPath("$.error.message") { value("Task access denied: task-other") }
        }
    }
}
```

- [ ] **Step 2: 실패 확인**

Run: `./gradlew :apps:api:test --tests 'com.bara.api.adapter.in.rest.A2AExceptionHandlerTest'`
Expected: `A2AExceptionHandler` 클래스가 존재하지 않아 컴파일 실패.

- [ ] **Step 3: A2AExceptionHandler 구현**

```kotlin
// apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/A2AExceptionHandler.kt
package com.bara.api.adapter.`in`.rest

import com.bara.api.adapter.`in`.rest.a2a.JsonRpcError
import com.bara.api.adapter.`in`.rest.a2a.JsonRpcResponse
import com.bara.api.domain.exception.A2AErrorCodes
import com.bara.api.domain.exception.A2AException
import com.bara.common.logging.WideEvent
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class A2AExceptionHandler {

    @ExceptionHandler(A2AException::class)
    fun handleA2A(ex: A2AException): ResponseEntity<JsonRpcResponse<Nothing>> {
        val httpStatus = httpFor(ex.code)
        val outcome = outcomeFor(ex.code)
        WideEvent.put("error_type", ex.javaClass.simpleName)
        WideEvent.put("error_code", ex.code)
        WideEvent.put("outcome", outcome)
        WideEvent.message("A2A 예외: ${ex.message}")

        return ResponseEntity.status(httpStatus).body(
            JsonRpcResponse(
                id = null,
                result = null,
                error = JsonRpcError(code = ex.code, message = ex.message ?: "A2A error"),
            ),
        )
    }

    private fun httpFor(code: Int): HttpStatus = when (code) {
        A2AErrorCodes.KAFKA_PUBLISH_FAILED -> HttpStatus.BAD_GATEWAY
        A2AErrorCodes.AGENT_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE
        A2AErrorCodes.AGENT_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT
        A2AErrorCodes.TASK_NOT_FOUND -> HttpStatus.NOT_FOUND
        A2AErrorCodes.TASK_ACCESS_DENIED -> HttpStatus.FORBIDDEN
        else -> HttpStatus.INTERNAL_SERVER_ERROR
    }

    private fun outcomeFor(code: Int): String = when (code) {
        A2AErrorCodes.KAFKA_PUBLISH_FAILED -> "kafka_publish_failed"
        A2AErrorCodes.AGENT_UNAVAILABLE -> "agent_unavailable"
        A2AErrorCodes.AGENT_TIMEOUT -> "agent_timeout"
        A2AErrorCodes.TASK_NOT_FOUND -> "task_not_found"
        A2AErrorCodes.TASK_ACCESS_DENIED -> "task_access_denied"
        else -> "a2a_error"
    }
}
```

- [ ] **Step 4: ApiExceptionHandler 에서 A2A 관련 3개 handler 제거**

`apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/ApiExceptionHandler.kt` 에서 다음 세 함수와 그들의 import 를 삭제한다:

- `handleAgentUnavailable(ex: AgentUnavailableException)`
- `handleAgentTimeout(ex: AgentTimeoutException)`
- `handleKafkaPublish(ex: KafkaPublishException)`

또한 아래 import 구문도 함께 삭제:

```
import com.bara.api.domain.exception.AgentTimeoutException
import com.bara.api.domain.exception.AgentUnavailableException
import com.bara.api.domain.exception.KafkaPublishException
```

정리 후 `ApiExceptionHandler` 에 남는 handler 는 다음 4개:

- `handleAgentNotFound`
- `handleAgentNameAlreadyExists`
- `handleAgentOwnership`
- `handleAgentNotRegistered`

(이들은 CRUD 용 REST 응답이므로 기존 `ErrorResponse` 포맷을 유지한다.)

- [ ] **Step 5: A2AExceptionHandlerTest 통과 확인**

Run: `./gradlew :apps:api:test --tests 'com.bara.api.adapter.in.rest.A2AExceptionHandlerTest'`
Expected: 5개 테스트 모두 PASS.

- [ ] **Step 6: AgentControllerTest 에서 A2A 예외 3개 테스트의 assertion 을 envelope 으로 선반영**

현재 `AgentControllerTest.kt` 에 다음 3개 에러 테스트가 있다 (확인된 라인):

- line 309: `POST agents message send Agent 비활성 시 503` — `AgentUnavailableException()` throw, `jsonPath("$.error") { value("agent_unavailable") }` 검사
- line 321: `POST agents message send Agent 타임아웃 시 504` — async dispatch 후 `MockMvcResultMatchers.jsonPath("$.error").value("agent_timeout")` 검사
- line 340: `POST agents message send Kafka 실패 시 502` — `jsonPath("$.error") { value("kafka_publish_failed") }` 검사

`ApiExceptionHandler` 에서 handler 를 제거한 순간 이 3개 테스트는 `A2AExceptionHandler` 가 돌려주는 envelope 을 받는다. 본 Task 커밋이 초록을 유지하려면 **이 3개 테스트 assertion 만** 선반영. Happy path (`POST agents message send 블로킹 완료 시 200`, 대략 line 270-305) 와 register/list/get 같은 다른 테스트는 **이번 Task 에서는 건드리지 않는다**.

(a) 파일 상단에 import 와 `@Import` 업데이트:

```kotlin
import com.bara.api.domain.exception.A2AErrorCodes
```

```kotlin
@Import(ApiExceptionHandler::class, A2AExceptionHandler::class)
```

(b) 3개 테스트 응답 검증을 envelope 기대로 교체:

```kotlin
    @Test
    fun `POST agents message send Agent 비활성 시 503`() {
        every { sendMessageUseCase.sendBlocking(any(), eq("dead"), any()) } throws AgentUnavailableException()

        mockMvc.post("/agents/dead/message:send") {
            header("X-User-Id", "user-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"message":{"messageId":"msg-1","parts":[{"text":"hi"}]}}"""
        }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.jsonrpc") { value("2.0") }
            jsonPath("$.error.code") { value(A2AErrorCodes.AGENT_UNAVAILABLE) }
            jsonPath("$.error.message") { value("Agent is not available") }
            jsonPath("$.result") { doesNotExist() }
        }
    }
```

Timeout 테스트의 `asyncDispatch` 경로는 matcher 스타일을 유지하되 jsonPath 만 교체:

```kotlin
        mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(mvcResult))
            .andExpect(MockMvcResultMatchers.status().isGatewayTimeout)
            .andExpect(MockMvcResultMatchers.jsonPath("$.error.code").value(A2AErrorCodes.AGENT_TIMEOUT))
            .andExpect(MockMvcResultMatchers.jsonPath("$.error.message").value("Agent did not respond within timeout"))
```

Kafka 실패:

```kotlin
        }.andExpect {
            status { isBadGateway() }
            jsonPath("$.error.code") { value(A2AErrorCodes.KAFKA_PUBLISH_FAILED) }
            jsonPath("$.error.message") { value("broker down") }
        }
```

Happy path 테스트의 request body (`{"message":{"messageId"...}}`) 와 response assertion (`$.id`, `$.status.state`) 은 **그대로 둔다** — 아직 컨트롤러가 envelope 을 반환하지 않으므로.

- [ ] **Step 7: 전체 api 모듈 test green 확인**

Run: `./gradlew :apps:api:test`
Expected: BUILD SUCCESSFUL. A2AExceptionHandlerTest 5개 + AgentControllerTest 전체 (수정된 에러 3개 + 나머지 기존) 모두 PASS.

- [ ] **Step 8: commit**

```bash
git add apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/A2AExceptionHandler.kt \
        apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/ApiExceptionHandler.kt \
        apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/A2AExceptionHandlerTest.kt \
        apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/AgentControllerTest.kt
git commit -m "feat(api): add A2AExceptionHandler returning JSON-RPC error envelopes"
```

---

## Task 5: GetTaskQuery port + GetTaskService 구현

**Files:**

- Create: `apps/api/src/main/kotlin/com/bara/api/application/port/in/query/GetTaskQuery.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/application/service/query/GetTaskService.kt`
- Create: `apps/api/src/test/kotlin/com/bara/api/application/service/query/GetTaskServiceTest.kt`

**배경**: `TaskRepositoryPort.findById(id: String): Task?` 는 Phase 1 에 이미 존재한다. Service 는:

1. `findById` 로 조회
2. null 이면 `TaskNotFoundException`
3. `task.userId != userId` 면 `TaskAccessDeniedException`
4. 매칭되면 `A2ATaskMapper.toDto(task)`

spec §2.5 는 `findByIdAndUserId(id, userId)` 를 제안하지만, 404 와 403 을 구분하기 위해 service layer 에서 수동으로 분기하는 편이 더 명확하다 → spec 의 port 이름 그대로 따르지 않고 **기존 `findById` 만 사용**한다 (YAGNI).

- [ ] **Step 1: GetTaskQuery port 작성**

```kotlin
// apps/api/src/main/kotlin/com/bara/api/application/port/in/query/GetTaskQuery.kt
package com.bara.api.application.port.`in`.query

import com.bara.api.adapter.`in`.rest.a2a.A2ATaskDto

interface GetTaskQuery {
    /**
     * [userId] 소유의 [taskId] Task 를 조회해 A2A wire DTO 로 변환한다.
     * @throws com.bara.api.domain.exception.TaskNotFoundException taskId 가 Mongo 에 없을 때
     * @throws com.bara.api.domain.exception.TaskAccessDeniedException 소유자가 다를 때
     */
    fun getTask(userId: String, taskId: String): A2ATaskDto
}
```

- [ ] **Step 2: GetTaskService 실패 테스트 작성**

```kotlin
// apps/api/src/test/kotlin/com/bara/api/application/service/query/GetTaskServiceTest.kt
package com.bara.api.application.service.query

import com.bara.api.application.port.out.TaskRepositoryPort
import com.bara.api.domain.exception.TaskAccessDeniedException
import com.bara.api.domain.exception.TaskNotFoundException
import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.A2APart
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskState
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertEquals

class GetTaskServiceTest {

    private val taskRepositoryPort = mockk<TaskRepositoryPort>()
    private val service = GetTaskService(taskRepositoryPort)

    private fun sampleTask(
        id: String = "task-1",
        userId: String = "user-1",
        state: TaskState = TaskState.COMPLETED,
    ): Task {
        val now = Instant.parse("2026-04-11T00:00:00Z")
        return Task(
            id = id,
            agentId = "agent-001",
            agentName = "my-agent",
            userId = userId,
            contextId = "ctx-1",
            state = state,
            inputMessage = A2AMessage("m-1", "user", listOf(A2APart("text", "hi"))),
            requestId = "r-1",
            createdAt = now,
            updatedAt = now,
            expiredAt = now.plusSeconds(7 * 24 * 3600),
        )
    }

    @Test
    fun `정상 조회 — 소유자 일치 시 A2ATaskDto 반환`() {
        every { taskRepositoryPort.findById("task-1") } returns sampleTask(state = TaskState.COMPLETED)

        val dto = service.getTask(userId = "user-1", taskId = "task-1")

        assertEquals("task-1", dto.id)
        assertEquals("completed", dto.status.state)
        assertEquals("ctx-1", dto.contextId)
    }

    @Test
    fun `Task 미존재 — TaskNotFoundException`() {
        every { taskRepositoryPort.findById("missing") } returns null

        val ex = assertThrows<TaskNotFoundException> {
            service.getTask(userId = "user-1", taskId = "missing")
        }
        assertEquals("Task not found: missing", ex.message)
    }

    @Test
    fun `소유자 불일치 — TaskAccessDeniedException`() {
        every { taskRepositoryPort.findById("task-1") } returns sampleTask(userId = "owner")

        val ex = assertThrows<TaskAccessDeniedException> {
            service.getTask(userId = "attacker", taskId = "task-1")
        }
        assertEquals("Task access denied: task-1", ex.message)
    }
}
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :apps:api:test --tests 'com.bara.api.application.service.query.GetTaskServiceTest'`
Expected: `GetTaskService` 클래스가 존재하지 않아 컴파일 실패.

- [ ] **Step 4: GetTaskService 구현**

```kotlin
// apps/api/src/main/kotlin/com/bara/api/application/service/query/GetTaskService.kt
package com.bara.api.application.service.query

import com.bara.api.adapter.`in`.rest.a2a.A2ATaskDto
import com.bara.api.adapter.`in`.rest.a2a.A2ATaskMapper
import com.bara.api.application.port.`in`.query.GetTaskQuery
import com.bara.api.application.port.out.TaskRepositoryPort
import com.bara.api.domain.exception.TaskAccessDeniedException
import com.bara.api.domain.exception.TaskNotFoundException
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class GetTaskService(
    private val taskRepositoryPort: TaskRepositoryPort,
) : GetTaskQuery {

    override fun getTask(userId: String, taskId: String): A2ATaskDto {
        val task = taskRepositoryPort.findById(taskId)
        if (task == null) {
            WideEvent.put("task_id", taskId)
            WideEvent.put("user_id", userId)
            WideEvent.put("outcome", "task_not_found")
            WideEvent.message("Task 조회 실패 - 존재하지 않음")
            throw TaskNotFoundException(taskId)
        }

        if (task.userId != userId) {
            WideEvent.put("task_id", taskId)
            WideEvent.put("user_id", userId)
            WideEvent.put("outcome", "task_access_denied")
            WideEvent.message("Task 조회 실패 - 권한 없음")
            throw TaskAccessDeniedException(taskId)
        }

        WideEvent.put("task_id", taskId)
        WideEvent.put("user_id", userId)
        WideEvent.put("current_state", task.state.name.lowercase())
        WideEvent.put("outcome", "task_retrieved")
        WideEvent.message("Task 조회 성공")

        return A2ATaskMapper.toDto(task)
    }
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :apps:api:test --tests 'com.bara.api.application.service.query.GetTaskServiceTest'`
Expected: 3개 테스트 모두 PASS.

- [ ] **Step 6: commit**

```bash
git add apps/api/src/main/kotlin/com/bara/api/application/port/in/query/GetTaskQuery.kt \
        apps/api/src/main/kotlin/com/bara/api/application/service/query/GetTaskService.kt \
        apps/api/src/test/kotlin/com/bara/api/application/service/query/GetTaskServiceTest.kt
git commit -m "feat(api): add GetTaskService for A2A task polling"
```

---

## Task 6: SendMessageUseCase.sendAsync + SendMessageService 구현

**Files:**

- Modify: `apps/api/src/main/kotlin/com/bara/api/application/port/in/command/SendMessageUseCase.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/application/service/command/SendMessageService.kt`
- Modify: `apps/api/src/test/kotlin/com/bara/api/application/service/command/SendMessageServiceTest.kt`

**배경**: `sendAsync` 는 `sendBlocking` 에서 `await` 등록과 `future.handle` 블록만 제거한 버전이다. Task 생성 (Mongo insert `submitted`) → EventBus publish → Kafka publish (ack 대기 유지; 실패 시 `failed` 전환) → 즉시 `A2ATaskDto(state="submitted")` 반환.

중복 코드를 피하기 위해 공통 단계 4개 (agentId resolve, Task 생성, eventBus publish, Kafka publish) 를 private helper 로 추출할 수도 있지만 **Phase 3 에서 SSE 용 `sendStream` 이 또 추가될 예정** 이므로, 본 Phase 에서는 중복을 허용하고 Phase 3 에서 common extraction 을 검토한다 (YAGNI + 조기 추상화 방지).

- [ ] **Step 1: SendMessageUseCase 에 sendAsync 추가**

```kotlin
// apps/api/src/main/kotlin/com/bara/api/application/port/in/command/SendMessageUseCase.kt
package com.bara.api.application.port.`in`.command

import com.bara.api.adapter.`in`.rest.a2a.A2ATaskDto
import java.util.concurrent.CompletableFuture

interface SendMessageUseCase {
    fun sendBlocking(
        userId: String,
        agentName: String,
        request: SendMessageRequest,
    ): CompletableFuture<A2ATaskDto>

    /**
     * Async submit: Kafka publish ack 까지만 기다린 후 즉시 submitted Task 를 DTO 로 반환한다.
     * 결과는 클라이언트가 GET /agents/{name}/tasks/{taskId} 로 폴링한다.
     * Agent 응답 대기/결과 변환은 하지 않는다.
     */
    fun sendAsync(
        userId: String,
        agentName: String,
        request: SendMessageRequest,
    ): A2ATaskDto

    data class SendMessageRequest(
        val messageId: String,
        val text: String,
        val contextId: String?,
    )
}
```

- [ ] **Step 2: 기존 SendMessageServiceTest 에 sendAsync 실패 테스트 추가**

파일 맨 아래 (`)` 닫기 전) 에 다음 테스트를 추가:

```kotlin
    @Test
    fun `sendAsync — Kafka publish 성공 시 submitted 상태 DTO 즉시 반환`() {
        every { agentRegistryPort.getAgentId("my-agent") } returns "agent-001"
        val taskSlot = slot<Task>()
        every { taskRepositoryPort.save(capture(taskSlot)) } answers { taskSlot.captured }
        every { taskEventBusPort.publish(any(), any()) } returns "1-0"
        justRun { taskPublisherPort.publish("agent-001", any<TaskMessagePayload>()) }

        val dto = service.sendAsync(
            userId = "user-1",
            agentName = "my-agent",
            request = request,
        )

        assertEquals("submitted", dto.status.state)
        assertEquals(taskSlot.captured.id, dto.id)
        assertEquals("c-1", dto.contextId)
        verify(exactly = 0) { taskEventBusPort.await(any(), any()) }
    }

    @Test
    fun `sendAsync — AgentRegistry 미등록 시 AgentUnavailableException`() {
        every { agentRegistryPort.getAgentId("my-agent") } returns null

        val ex = runCatching {
            service.sendAsync(
                userId = "user-1",
                agentName = "my-agent",
                request = request,
            )
        }.exceptionOrNull()
        assertTrue(ex is AgentUnavailableException)
        verify(exactly = 0) { taskRepositoryPort.save(any()) }
        verify(exactly = 0) { taskPublisherPort.publish(any(), any()) }
    }

    @Test
    fun `sendAsync — Kafka publish 실패 시 Task를 failed로 전환 후 KafkaPublishException 전파`() {
        every { agentRegistryPort.getAgentId("my-agent") } returns "agent-001"
        val taskSlot = slot<Task>()
        every { taskRepositoryPort.save(capture(taskSlot)) } answers { taskSlot.captured }
        every { taskRepositoryPort.updateState(
            id = any(),
            state = TaskState.FAILED,
            statusMessage = null,
            artifacts = emptyList(),
            errorCode = "kafka-publish-failed",
            errorMessage = any(),
            updatedAt = any(),
            completedAt = any(),
            expiredAt = any(),
        ) } returns true
        every { taskEventBusPort.publish(any(), any()) } returns "1-0"
        every {
            taskPublisherPort.publish("agent-001", any<TaskMessagePayload>())
        } throws KafkaPublishException("broker down")

        val ex = runCatching {
            service.sendAsync(
                userId = "user-1",
                agentName = "my-agent",
                request = request,
            )
        }.exceptionOrNull()
        assertTrue(ex is KafkaPublishException)
        verify(exactly = 1) {
            taskRepositoryPort.updateState(
                id = any(),
                state = TaskState.FAILED,
                statusMessage = null,
                artifacts = emptyList(),
                errorCode = "kafka-publish-failed",
                errorMessage = any(),
                updatedAt = any(),
                completedAt = any(),
                expiredAt = any(),
            )
        }
    }
```

- [ ] **Step 3: 실패 확인**

Run: `./gradlew :apps:api:test --tests 'com.bara.api.application.service.command.SendMessageServiceTest'`
Expected: `sendAsync` 메서드가 interface 에 없다는 컴파일 에러.

- [ ] **Step 4: SendMessageService.sendAsync 구현**

`SendMessageService.kt` 에 다음 메서드를 `sendBlocking` 아래에 추가:

```kotlin
    override fun sendAsync(
        userId: String,
        agentName: String,
        request: SendMessageUseCase.SendMessageRequest,
    ): A2ATaskDto {
        val agentId = agentRegistryPort.getAgentId(agentName)
            ?: throw AgentUnavailableException()

        val now = Instant.now()
        val taskId = UUID.randomUUID().toString()
        val requestId = UUID.randomUUID().toString()
        val contextId = request.contextId ?: UUID.randomUUID().toString()
        val expiredAt = now.plus(Duration.ofDays(properties.mongoTtlDays))

        val inputMessage = A2AMessage(
            messageId = request.messageId,
            role = "user",
            parts = listOf(A2APart(kind = "text", text = request.text)),
        )

        val task = Task(
            id = taskId,
            agentId = agentId,
            agentName = agentName,
            userId = userId,
            contextId = contextId,
            state = TaskState.SUBMITTED,
            inputMessage = inputMessage,
            requestId = requestId,
            createdAt = now,
            updatedAt = now,
            expiredAt = expiredAt,
        )
        taskRepositoryPort.save(task)
        taskEventBusPort.publish(taskId, TaskEvent.of(task))

        try {
            taskPublisherPort.publish(
                agentId = agentId,
                payload = TaskMessagePayload(
                    taskId = taskId,
                    contextId = contextId,
                    userId = userId,
                    requestId = requestId,
                    resultTopic = "results.api",
                    allowedAgents = emptyList(),
                    message = inputMessage,
                ),
            )
        } catch (e: KafkaPublishException) {
            markFailed(task, "kafka-publish-failed", e.message ?: "Kafka publish failed")
            WideEvent.put("task_id", taskId)
            WideEvent.put("agent_name", agentName)
            WideEvent.put("user_id", userId)
            WideEvent.put("return_immediately", true)
            WideEvent.put("outcome", "kafka_publish_failed")
            WideEvent.message("Kafka publish 실패 (async)")
            throw e
        }

        WideEvent.put("task_id", taskId)
        WideEvent.put("agent_name", agentName)
        WideEvent.put("agent_id", agentId)
        WideEvent.put("user_id", userId)
        WideEvent.put("return_immediately", true)
        WideEvent.put("outcome", "task_submitted")
        WideEvent.message("태스크 async 제출 완료")

        return A2ATaskMapper.toDto(task)
    }
```

- [ ] **Step 5: SendMessageServiceTest 통과 확인**

Run: `./gradlew :apps:api:test --tests 'com.bara.api.application.service.command.SendMessageServiceTest'`
Expected: 기존 블로킹 테스트 + 새 sendAsync 테스트 3개 모두 PASS.

- [ ] **Step 6: commit**

```bash
git add apps/api/src/main/kotlin/com/bara/api/application/port/in/command/SendMessageUseCase.kt \
        apps/api/src/main/kotlin/com/bara/api/application/service/command/SendMessageService.kt \
        apps/api/src/test/kotlin/com/bara/api/application/service/command/SendMessageServiceTest.kt
git commit -m "feat(api): add async submit mode to SendMessageService"
```

---

## Task 7: AgentDtos — SendMessageParams/ConfigurationRequest 추가, SendMessageApiRequest 제거

**Files:**

- Modify: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentDtos.kt`

**배경**: envelope 안에 들어갈 `params` 타입을 정의한다. spec §3 시나리오 B 의 `configuration.returnImmediately` 를 `ConfigurationRequest` 로 분리한다. 기존 `SendMessageApiRequest` 는 envelope 없이 body 최상위로 받던 구조였으므로 제거한다 (`MessageRequest`, `PartRequest` 는 재사용). `contextId` 는 params 레벨로 올린다 (spec 기준).

- [ ] **Step 1: AgentDtos.kt 의 A2A Message 섹션 교체**

파일의 `// ── A2A Message ──` 주석 이하 블록을 아래로 교체:

```kotlin
// ── A2A Message ──

data class SendMessageParams(
    val message: MessageRequest,
    val contextId: String? = null,
    val configuration: ConfigurationRequest? = null,
)

data class ConfigurationRequest(
    val returnImmediately: Boolean = false,
)

data class MessageRequest(
    val messageId: String,
    val parts: List<PartRequest>,
)

data class PartRequest(
    val text: String,
)
```

즉 기존 `SendMessageApiRequest` 클래스는 삭제하고, `SendMessageParams` + `ConfigurationRequest` 가 그 자리에 들어간다. `MessageRequest`, `PartRequest` 는 그대로 유지.

- [ ] **Step 2: 컴파일 확인 (예상: 깨짐)**

Run: `./gradlew :apps:api:compileKotlin`
Expected: `AgentController.sendMessage` 가 아직 `SendMessageApiRequest` 를 사용하므로 컴파일 실패. 다음 Task 에서 같이 해결한다.

**이 단계에서는 commit 하지 않는다.** Task 8 까지 한 번에 commit.

---

## Task 8: AgentController — sendMessage envelope + GET /tasks/{taskId} + getTaskQuery 주입

**Files:**

- Modify: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentController.kt`

**배경**:

1. `sendMessage` 가 `JsonRpcRequest<SendMessageParams>` envelope 을 받고 `CompletableFuture<ResponseEntity<JsonRpcResponse<A2ATaskDto>>>` 를 반환한다.
2. `params.configuration.returnImmediately` 분기: true 면 `sendMessageUseCase.sendAsync`, false 면 기존 `sendBlocking`.
3. 동일 컨트롤러에 `@GetMapping("/{agentName}/tasks/{taskId}")` 를 추가해 `getTaskQuery.getTask` 결과를 envelope 에 담아 반환.
4. 응답 envelope 의 `id` 는 요청 envelope 의 `id` 를 그대로 echo.

- [ ] **Step 1: AgentController 의 sendMessage + getTask 교체**

파일 최하단 `sendMessage` 함수 블록을 다음으로 교체 (아래는 전체 컨트롤러 중 변경 부분만):

```kotlin
import com.bara.api.adapter.`in`.rest.a2a.JsonRpcRequest
import com.bara.api.adapter.`in`.rest.a2a.JsonRpcResponse
import com.bara.api.application.port.`in`.query.GetTaskQuery
```

생성자에 `getTaskQuery: GetTaskQuery` 파라미터 추가:

```kotlin
@RestController
@RequestMapping("/agents")
class AgentController(
    private val registerAgentUseCase: RegisterAgentUseCase,
    private val deleteAgentUseCase: DeleteAgentUseCase,
    private val registryAgentUseCase: RegistryAgentUseCase,
    private val heartbeatAgentUseCase: HeartbeatAgentUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    private val listAgentsQuery: ListAgentsQuery,
    private val getAgentQuery: GetAgentQuery,
    private val getAgentCardQuery: GetAgentCardQuery,
    private val getTaskQuery: GetTaskQuery,
) {
```

기존 `sendMessage` 를 제거하고 아래로 교체:

```kotlin
    @PostMapping("/{agentName}/message:send")
    fun sendMessage(
        @RequestHeader("X-User-Id") userId: String,
        @PathVariable agentName: String,
        @RequestBody envelope: JsonRpcRequest<SendMessageParams>,
    ): CompletableFuture<ResponseEntity<JsonRpcResponse<A2ATaskDto>>> {
        val params = envelope.params
            ?: throw IllegalArgumentException("JSON-RPC params is required")
        val text = params.message.parts.firstOrNull()?.text ?: ""
        val sendRequest = SendMessageUseCase.SendMessageRequest(
            messageId = params.message.messageId,
            text = text,
            contextId = params.contextId,
        )
        val returnImmediately = params.configuration?.returnImmediately == true

        return if (returnImmediately) {
            val dto = sendMessageUseCase.sendAsync(userId, agentName, sendRequest)
            CompletableFuture.completedFuture(
                ResponseEntity.ok(JsonRpcResponse(id = envelope.id, result = dto)),
            )
        } else {
            sendMessageUseCase.sendBlocking(userId, agentName, sendRequest)
                .thenApply { dto ->
                    ResponseEntity.ok(JsonRpcResponse(id = envelope.id, result = dto))
                }
        }
    }

    @GetMapping("/{agentName}/tasks/{taskId}")
    fun getTask(
        @RequestHeader("X-User-Id") userId: String,
        @PathVariable agentName: String,
        @PathVariable taskId: String,
    ): ResponseEntity<JsonRpcResponse<A2ATaskDto>> {
        val dto = getTaskQuery.getTask(userId = userId, taskId = taskId)
        return ResponseEntity.ok(JsonRpcResponse(id = null, result = dto))
    }
```

**주의사항**:

- `agentName` 은 URL path 에 살아있지만 `getTask` 에서는 **실제로 사용하지 않는다** (taskId 만으로 Mongo 조회 가능). spec §3 scenario B 의 URL 형태를 맞추기 위해 경로에만 유지. 향후 "agent 소유권 이중 확인" 이 필요해지면 사용.
- `getTask` 는 `ResponseEntity<JsonRpcResponse<A2ATaskDto>>` — `CompletableFuture` 아님. 동기 Mongo 조회.
- `envelope.params` null 이면 `IllegalArgumentException` → 기본 Spring 400. Phase 2 에서는 이 경로를 특별히 처리하지 않음 (FE/테스트에서 항상 params 를 보내므로). 필요 시 Phase 3 에서 `-32602 Invalid params` 처리 추가.

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :apps:api:compileKotlin`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: AgentControllerTest 의 Happy path 블로킹 테스트를 envelope 로 교체**

Task 4 에서 에러 3개는 이미 envelope 으로 옮겼지만, Happy path 블로킹 테스트 (대략 `AgentControllerTest.kt` line 270~305, `asyncStarted` + `asyncDispatch` 경로로 `$.id` 와 `$.status.state` 를 검사) 는 아직 구버전 포맷이다. 컨트롤러가 이제 envelope 을 반환하므로 본 Task commit 에 같이 업데이트해야 빌드가 유지된다.

(a) 요청 body 를 envelope 으로 교체. 기존 `"""{"message":{"messageId":"msg-1","parts":[{"text":"hello"}]},"contextId":"ctx-1"}"""` 를 다음으로 교체:

```kotlin
content = """
    {
        "jsonrpc": "2.0",
        "id": "req-1",
        "method": "message/send",
        "params": {
            "message": {
                "messageId": "msg-1",
                "parts": [{"text":"hello"}]
            },
            "contextId": "ctx-1"
        }
    }
""".trimIndent()
```

(b) 응답 assertion 을 envelope 으로 교체:

```kotlin
mockMvc.perform(MockMvcRequestBuilders.asyncDispatch(mvcResult))
    .andExpect(MockMvcResultMatchers.status().isOk)
    .andExpect(MockMvcResultMatchers.jsonPath("$.jsonrpc").value("2.0"))
    .andExpect(MockMvcResultMatchers.jsonPath("$.id").value("req-1"))
    .andExpect(MockMvcResultMatchers.jsonPath("$.result.id").value("t-1"))
    .andExpect(MockMvcResultMatchers.jsonPath("$.result.status.state").value("completed"))
```

- [ ] **Step 4: 단위 테스트 실행**

Run: `./gradlew :apps:api:test --tests 'com.bara.api.adapter.in.rest.AgentControllerTest'`
Expected: Happy path + 에러 3개 포함 모든 기존 테스트 PASS.

- [ ] **Step 5: commit (Task 7 + 8 묶음)**

```bash
git add apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentController.kt \
        apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentDtos.kt \
        apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/AgentControllerTest.kt
git commit -m "feat(api): wrap message:send and add GET tasks/{id} in JSON-RPC envelope"
```

---

## Task 9: AgentControllerTest — 신규 테스트 추가 (returnImmediately + getTask)

**Files:**

- Modify: `apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/AgentControllerTest.kt`

**배경**: 기존 테스트 4개 (Happy path + 에러 3개) 는 이미 Task 4/Task 8 에서 envelope 포맷으로 이전됐다. 본 Task 에서는 **순수 신규 테스트 4개**만 추가한다:

- `sendMessage — returnImmediately=true 일 때 sendAsync 를 호출하고 즉시 200 envelope 반환`
- `getTask — 정상 조회 시 200 envelope result 반환`
- `getTask — TaskNotFoundException 전파 시 A2AExceptionHandler 가 404 envelope 반환`
- `getTask — TaskAccessDeniedException 전파 시 A2AExceptionHandler 가 403 envelope 반환`

`GetTaskQuery` mock bean 을 새로 주입해야 하고, `A2ATaskDto`/`A2ATaskStatusDto` import 및 `TaskNotFoundException`/`TaskAccessDeniedException` import 가 필요하다.

- [ ] **Step 1: import + @MockkBean 추가**

파일 상단에 추가 (이미 있는 것은 skip):

```kotlin
import com.bara.api.adapter.`in`.rest.a2a.A2ATaskDto
import com.bara.api.adapter.`in`.rest.a2a.A2ATaskStatusDto
import com.bara.api.application.port.`in`.query.GetTaskQuery
import com.bara.api.domain.exception.TaskAccessDeniedException
import com.bara.api.domain.exception.TaskNotFoundException
```

클래스의 @MockkBean 블록에 추가:

```kotlin
    @MockkBean
    lateinit var getTaskQuery: GetTaskQuery
```

- [ ] **Step 2: 신규 테스트 — returnImmediately=true 분기**

다음 테스트를 클래스 하단 에러 케이스 테스트들 근처에 추가:

```kotlin
    @Test
    fun `sendMessage - returnImmediately true - sendAsync 호출 후 submitted DTO envelope 반환`() {
        val submittedDto = A2ATaskDto(
            id = "task-async-1",
            contextId = "ctx-1",
            status = A2ATaskStatusDto(state = "submitted", message = null, timestamp = Instant.parse("2026-04-11T00:00:00Z").toString()),
            artifacts = emptyList(),
        )
        every {
            sendMessageUseCase.sendAsync("user-1", "my-agent", any())
        } returns submittedDto

        val body = """
            {
                "jsonrpc": "2.0",
                "id": "req-async-1",
                "method": "message/send",
                "params": {
                    "message": {
                        "messageId": "m-async-1",
                        "parts": [{"text": "hi"}]
                    },
                    "configuration": { "returnImmediately": true }
                }
            }
        """.trimIndent()

        mockMvc.post("/agents/my-agent/message:send") {
            contentType = MediaType.APPLICATION_JSON
            header("X-User-Id", "user-1")
            content = body
        }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value("req-async-1") }
            jsonPath("$.result.id") { value("task-async-1") }
            jsonPath("$.result.status.state") { value("submitted") }
        }
    }
```

(기존 파일에 이미 `every { sendMessageUseCase.sendBlocking(...) }` mock 설정이 있으므로 `sendAsync` 도 별도 mock 이 필요. 본 테스트는 독립적으로 `sendAsync` 만 mock 함.)

- [ ] **Step 3: 신규 테스트 — GET tasks/{taskId} happy path**

```kotlin
    @Test
    fun `getTask - 정상 조회 - 200 envelope result 반환`() {
        val dto = A2ATaskDto(
            id = "task-1",
            contextId = "ctx-1",
            status = A2ATaskStatusDto(state = "completed", message = null, timestamp = Instant.parse("2026-04-11T00:00:00Z").toString()),
            artifacts = emptyList(),
        )
        every { getTaskQuery.getTask("user-1", "task-1") } returns dto

        mockMvc.get("/agents/my-agent/tasks/task-1") {
            header("X-User-Id", "user-1")
        }.andExpect {
            status { isOk() }
            jsonPath("$.jsonrpc") { value("2.0") }
            jsonPath("$.result.id") { value("task-1") }
            jsonPath("$.result.status.state") { value("completed") }
            jsonPath("$.error") { doesNotExist() }
        }
    }
```

- [ ] **Step 4: 신규 테스트 — GET tasks/{taskId} 404**

```kotlin
    @Test
    fun `getTask - TaskNotFoundException - 404 envelope error`() {
        every { getTaskQuery.getTask("user-1", "missing") } throws TaskNotFoundException("missing")

        mockMvc.get("/agents/my-agent/tasks/missing") {
            header("X-User-Id", "user-1")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value(A2AErrorCodes.TASK_NOT_FOUND) }
            jsonPath("$.error.message") { value("Task not found: missing") }
        }
    }
```

- [ ] **Step 5: 신규 테스트 — GET tasks/{taskId} 403**

```kotlin
    @Test
    fun `getTask - TaskAccessDeniedException - 403 envelope error`() {
        every { getTaskQuery.getTask("attacker", "task-1") } throws TaskAccessDeniedException("task-1")

        mockMvc.get("/agents/my-agent/tasks/task-1") {
            header("X-User-Id", "attacker")
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value(A2AErrorCodes.TASK_ACCESS_DENIED) }
            jsonPath("$.error.message") { value("Task access denied: task-1") }
        }
    }
```

- [ ] **Step 6: 테스트 실행**

Run: `./gradlew :apps:api:test --tests 'com.bara.api.adapter.in.rest.AgentControllerTest'`
Expected: 모든 테스트 PASS (기존 4개 envelope 교체본 + 신규 4개 = 총 8개 + 기존 non-message 테스트들).

- [ ] **Step 7: commit**

```bash
git add apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/AgentControllerTest.kt
git commit -m "test(api): add AgentControllerTest cases for async submit and getTask"
```

> **메모**: 초안에는 FE `AgentsPage.tsx` 업데이트 Task 가 있었으나, 해당 파일이 CRUD 전용이고 `message:send` 호출이 없음을 확인한 뒤 삭제했다. FE 에 채팅 UI 가 추가될 때 envelope 포맷으로 새로 구현한다.

---

## Task 10: e2e TaskProcessingScenarioTest — 기존 업데이트 + 신규 시나리오

**Files:**

- Modify: `apps/api/src/e2eTest/kotlin/com/bara/api/e2e/scenario/TaskProcessingScenarioTest.kt`

**배경**: 응답 포맷이 envelope 이 되었으므로 기존 5개 테스트 중 1-4 는 jsonPath assertion 을 업데이트해야 한다. 5 (TTL index) 는 envelope 무관이라 그대로 유지. 추가로 신규 시나리오 4개 (returnImmediately, GET tasks success/404/403) 를 넣는다.

기존 `sendMessage` helper 는 envelope request 를 보내고 envelope response 를 반환하도록 바꾼다. envelope 을 벗기는 것은 각 테스트에서 직접 (`body["result"]` / `body["error"]`) 수행.

- [ ] **Step 1: sendMessage 헬퍼 업데이트**

`TaskProcessingScenarioTest.kt` 의 `sendMessage` 함수를 envelope request 를 보내도록 교체:

```kotlin
    private fun sendMessage(
        userId: String,
        agentName: String,
        text: String,
        returnImmediately: Boolean = false,
    ): org.springframework.http.ResponseEntity<Map<*, *>> {
        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set("X-User-Id", userId)
        }
        val config = if (returnImmediately) {
            """, "configuration": { "returnImmediately": true }"""
        } else {
            ""
        }
        val body = """
            {
                "jsonrpc": "2.0",
                "id": "req-${UUID.randomUUID()}",
                "method": "message/send",
                "params": {
                    "message": {
                        "messageId": "${UUID.randomUUID()}",
                        "parts": [{"text": "$text"}]
                    }$config
                }
            }
        """.trimIndent()
        @Suppress("UNCHECKED_CAST")
        return http.exchange(
            url("/api/core/agents/$agentName/message:send"),
            HttpMethod.POST,
            HttpEntity(body, headers),
            Map::class.java,
        ) as org.springframework.http.ResponseEntity<Map<*, *>>
    }

    private fun getTask(
        userId: String,
        agentName: String,
        taskId: String,
    ): org.springframework.http.ResponseEntity<Map<*, *>> {
        val headers = HttpHeaders().apply { set("X-User-Id", userId) }
        @Suppress("UNCHECKED_CAST")
        return http.exchange(
            url("/api/core/agents/$agentName/tasks/$taskId"),
            HttpMethod.GET,
            HttpEntity<Void>(headers),
            Map::class.java,
        ) as org.springframework.http.ResponseEntity<Map<*, *>>
    }
```

- [ ] **Step 2: 기존 테스트 1 (Happy path) envelope 로 업데이트**

기존 assertion 을 envelope 으로 교체:

```kotlin
    @Test
    fun `1 - Happy path - FakeAgent가 completed 발행하면 200 + state=completed`() {
        val provider = "e2e-task-${UUID.randomUUID()}"
        val agentName = "happy-${UUID.randomUUID()}"
        val agentId = registerAgent(provider, agentName)
        registry(provider, agentName)
        fakeAgent.onAgent(agentId, FakeAgentKafka.Behavior.completed("pong"))

        val response = sendMessage(userId = "user-1", agentName = agentName, text = "ping")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        assertThat(body["jsonrpc"]).isEqualTo("2.0")
        assertThat(body["error"]).isNull()
        val result = body["result"] as Map<*, *>
        val status = result["status"] as Map<*, *>
        assertThat(status["state"]).isEqualTo("completed")

        val artifacts = result["artifacts"] as List<*>
        assertThat(artifacts).hasSize(1)
        val artifact = artifacts[0] as Map<*, *>
        val parts = artifact["parts"] as List<*>
        val part = parts[0] as Map<*, *>
        assertThat(part["text"]).isEqualTo("pong")
    }
```

- [ ] **Step 3: 기존 테스트 2 (Failed) envelope 로 업데이트**

```kotlin
    @Test
    fun `2 - FakeAgent가 failed 발행하면 200 + state=failed`() {
        val provider = "e2e-task-${UUID.randomUUID()}"
        val agentName = "failing-${UUID.randomUUID()}"
        val agentId = registerAgent(provider, agentName)
        registry(provider, agentName)
        fakeAgent.onAgent(
            agentId,
            FakeAgentKafka.Behavior.failed(errorCode = "agent_error", errorMessage = "intentional"),
        )

        val response = sendMessage(userId = "user-2", agentName = agentName, text = "boom")

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        val result = body["result"] as Map<*, *>
        val status = result["status"] as Map<*, *>
        assertThat(status["state"]).isEqualTo("failed")
    }
```

- [ ] **Step 4: 기존 테스트 3 (Silent → 504) envelope 로 업데이트**

```kotlin
    @Test
    fun `3 - Agent 침묵 - block-timeout-seconds 후 504 agent_timeout envelope`() {
        val provider = "e2e-task-${UUID.randomUUID()}"
        val agentName = "silent-${UUID.randomUUID()}"
        val agentId = registerAgent(provider, agentName)
        registry(provider, agentName)
        fakeAgent.onAgent(agentId, FakeAgentKafka.Behavior.Silent)

        val started = System.currentTimeMillis()
        val response = sendMessage(userId = "user-3", agentName = agentName, text = "hello?")
        val elapsed = System.currentTimeMillis() - started

        assertThat(response.statusCode).isEqualTo(HttpStatus.GATEWAY_TIMEOUT)
        val body = response.body!!
        val error = body["error"] as Map<*, *>
        assertThat((error["code"] as Number).toInt()).isEqualTo(-32063)
        assertThat(elapsed).isBetween(14_000L, 25_000L)
    }
```

- [ ] **Step 5: 기존 테스트 4 (unregistered → 503) envelope 로 업데이트**

```kotlin
    @Test
    fun `4 - registry 생략하면 503 agent_unavailable envelope`() {
        val provider = "e2e-task-${UUID.randomUUID()}"
        val agentName = "unreg-${UUID.randomUUID()}"
        registerAgent(provider, agentName)

        val response = sendMessage(userId = "user-4", agentName = agentName, text = "hi")

        assertThat(response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        val body = response.body!!
        val error = body["error"] as Map<*, *>
        assertThat((error["code"] as Number).toInt()).isEqualTo(-32062)
    }
```

- [ ] **Step 6: 기존 테스트 5 (TTL index) 는 그대로 유지**

TTL 인덱스 테스트는 HTTP 응답 바디를 검사하지 않으므로 수정 없음. 단 sendMessage helper 가 envelope 을 보내도록 바뀌었으므로 내부적으로 정상 동작해야 함.

- [ ] **Step 7: 신규 테스트 6 — returnImmediately=true happy path**

```kotlin
    @Test
    fun `6 - returnImmediately true - 즉시 200 + state=submitted envelope 반환`() {
        val provider = "e2e-task-${UUID.randomUUID()}"
        val agentName = "async-${UUID.randomUUID()}"
        val agentId = registerAgent(provider, agentName)
        registry(provider, agentName)
        // Behavior 가 없어도 OK — 동기 대기하지 않으므로
        fakeAgent.onAgent(agentId, FakeAgentKafka.Behavior.completed("ignored"))

        val started = System.currentTimeMillis()
        val response = sendMessage(
            userId = "user-6",
            agentName = agentName,
            text = "fire and forget",
            returnImmediately = true,
        )
        val elapsed = System.currentTimeMillis() - started

        assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
        val body = response.body!!
        val result = body["result"] as Map<*, *>
        val status = result["status"] as Map<*, *>
        assertThat(status["state"]).isEqualTo("submitted")
        // Kafka publish ack (5s timeout) 내로 응답. Agent 처리 시간 대기 없음.
        assertThat(elapsed).isLessThan(5_000L)
    }
```

- [ ] **Step 8: 신규 테스트 7 — GET tasks/{taskId} 터미널 상태 조회**

```kotlin
    @Test
    fun `7 - GET tasks taskId - Agent 완료 후 envelope result 반환`() {
        val provider = "e2e-task-${UUID.randomUUID()}"
        val agentName = "poll-${UUID.randomUUID()}"
        val agentId = registerAgent(provider, agentName)
        registry(provider, agentName)
        fakeAgent.onAgent(agentId, FakeAgentKafka.Behavior.completed("polled"))

        // Blocking send 로 완료된 task 1개 생성
        val sendRes = sendMessage(userId = "user-7", agentName = agentName, text = "poll me")
        assertThat(sendRes.statusCode).isEqualTo(HttpStatus.OK)
        val sent = sendRes.body!!["result"] as Map<*, *>
        val taskId = sent["id"] as String

        val getRes = getTask(userId = "user-7", agentName = agentName, taskId = taskId)
        assertThat(getRes.statusCode).isEqualTo(HttpStatus.OK)
        val body = getRes.body!!
        assertThat(body["error"]).isNull()
        val result = body["result"] as Map<*, *>
        val status = result["status"] as Map<*, *>
        assertThat(status["state"]).isEqualTo("completed")
    }
```

- [ ] **Step 9: 신규 테스트 8 — GET tasks/{taskId} 존재하지 않음 → 404 envelope**

```kotlin
    @Test
    fun `8 - GET tasks taskId - 존재하지 않는 taskId → 404 task_not_found envelope`() {
        val provider = "e2e-task-${UUID.randomUUID()}"
        val agentName = "missing-${UUID.randomUUID()}"
        registerAgent(provider, agentName)
        registry(provider, agentName)

        val response = getTask(
            userId = "user-8",
            agentName = agentName,
            taskId = "does-not-exist-${UUID.randomUUID()}",
        )

        assertThat(response.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        val body = response.body!!
        val error = body["error"] as Map<*, *>
        assertThat((error["code"] as Number).toInt()).isEqualTo(-32064)
    }
```

- [ ] **Step 10: 신규 테스트 9 — GET tasks/{taskId} 다른 userId → 403 envelope**

```kotlin
    @Test
    fun `9 - GET tasks taskId - 다른 userId 접근 → 403 task_access_denied envelope`() {
        val provider = "e2e-task-${UUID.randomUUID()}"
        val agentName = "owned-${UUID.randomUUID()}"
        val agentId = registerAgent(provider, agentName)
        registry(provider, agentName)
        fakeAgent.onAgent(agentId, FakeAgentKafka.Behavior.completed("secret"))

        val sendRes = sendMessage(userId = "owner-9", agentName = agentName, text = "private")
        val taskId = (sendRes.body!!["result"] as Map<*, *>)["id"] as String

        val response = getTask(userId = "attacker-9", agentName = agentName, taskId = taskId)

        assertThat(response.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        val body = response.body!!
        val error = body["error"] as Map<*, *>
        assertThat((error["code"] as Number).toInt()).isEqualTo(-32065)
    }
```

- [ ] **Step 11: e2e 실행**

Run: `./gradlew :apps:api:e2eTest --tests 'com.bara.api.e2e.scenario.TaskProcessingScenarioTest'`
Expected: 기존 5개 + 신규 4개 = 총 9개 테스트 PASS. 시간은 테스트 3 (Silent block-timeout 15s) 이 지배적.

- [ ] **Step 12: commit**

```bash
git add apps/api/src/e2eTest/kotlin/com/bara/api/e2e/scenario/TaskProcessingScenarioTest.kt
git commit -m "test(api): extend e2e TaskProcessingScenarioTest for envelope and polling"
```

---

## Task 11: docs 업데이트 + 최종 전체 빌드

**Files:**

- Modify: `docs/guides/logging/flows/api-task-processing.md`

**배경**: Phase 2 에서 추가된 엔드포인트 2종(`sendAsync` 분기 + `GET /tasks/{taskId}`) 의 WideEvent 필드와 outcome 목록을 문서에 추가한다. spec §4.7 의 로깅 테이블을 Phase 2 에 해당하는 행만 발췌.

- [ ] **Step 1: `api-task-processing.md` 에 async + get tasks 섹션 추가**

파일 아래에 추가:

```markdown
## POST /agents/{agentName}/message:send (returnImmediately=true 비동기)

| 필드               | 값                     | 설명                      |
| ------------------ | ---------------------- | ------------------------- |
| task_id            | UUID                   | 생성된 Task ID            |
| agent_name         | 문자열                 | 대상 Agent 이름           |
| agent_id           | UUID                   | 레지스트리 resolve 결과   |
| user_id            | UUID                   | 요청 User ID              |
| return_immediately | true                   | 비동기 분기 식별          |
| outcome            | `task_submitted`       | Kafka ack 성공, 즉시 반환 |
| outcome            | `agent_unavailable`    | Registry 미등록           |
| outcome            | `kafka_publish_failed` | Kafka publish ack 실패    |

## GET /agents/{agentName}/tasks/{taskId}

| 필드          | 값                                                               | 설명                                |
| ------------- | ---------------------------------------------------------------- | ----------------------------------- |
| task_id       | UUID                                                             | 조회 대상 Task                      |
| user_id       | UUID                                                             | 요청 User ID                        |
| current_state | `submitted`/`working`/`completed`/`failed`/`canceled`/`rejected` | 현재 상태                           |
| outcome       | `task_retrieved`                                                 | 정상 조회                           |
| outcome       | `task_not_found`                                                 | Mongo 에 taskId 없음 (-32064 / 404) |
| outcome       | `task_access_denied`                                             | userId 불일치 (-32065 / 403)        |

## A2AExceptionHandler 공통 필드

모든 A2AException (AgentUnavailable/AgentTimeout/KafkaPublish/TaskNotFound/TaskAccessDenied) 은 `A2AExceptionHandler` 에서 다음 필드를 추가 기록한다:

| 필드       | 값                        |
| ---------- | ------------------------- |
| error_type | 예외 클래스 simpleName    |
| error_code | JSON-RPC 에러 코드 (음수) |
| outcome    | 위 각 엔드포인트 표 참조  |
```

- [ ] **Step 2: commit**

```bash
git add docs/guides/logging/flows/api-task-processing.md
git commit -m "docs(api): add Phase 2 logging fields for async submit and task polling"
```

- [ ] **Step 3: 전체 apps:api 빌드 + 단위 테스트**

Run: `./gradlew :apps:api:build`
Expected: BUILD SUCCESSFUL. 모든 Kotlin compileKotlin / compileTestKotlin / test 태스크 녹색.

- [ ] **Step 4: e2eTest 전체 실행**

Run: `./gradlew :apps:api:e2eTest`
Expected: AgentErrorTest (4) + AgentFlowScenarioTest (6) + TaskProcessingScenarioTest (9) = 19 tests 모두 PASS.

- [ ] **Step 5: 최종 확인 체크리스트**

- [ ] Phase 1 블로킹 경로가 여전히 동작 (TaskProcessingScenarioTest 1/2/3 통과)
- [ ] `returnImmediately=true` 분기가 즉시 `submitted` 반환 (TaskProcessingScenarioTest 6)
- [ ] `GET /agents/{name}/tasks/{id}` 조회 성공/404/403 모두 envelope 반환 (TaskProcessingScenarioTest 7/8/9)
- [ ] 모든 A2A 에러가 `JsonRpcResponse{error: JsonRpcError}` 포맷으로 반환 (A2AExceptionHandlerTest 5개 + e2e 에러 케이스)
- [ ] 기존 `ApiExceptionHandler` 는 CRUD 전용으로 축소되고 AgentNotFound/NameAlready/Ownership/NotRegistered 만 담당
- [ ] docs `api-task-processing.md` 에 Phase 2 엔드포인트 섹션 존재

---

## Phase 2 에서 의도적으로 **제외** 한 항목

Phase 3 에서 구현:

- `POST /agents/{name}/message:stream` SSE 엔드포인트 (시나리오 C)
- `GET /agents/{name}/tasks/{taskId}:subscribe` SSE 재연결 (시나리오 D)
- `Last-Event-ID` 기반 backfill
- Redis Stream pump thread 공유 패턴 (현재 per-subscription ScheduledExecutor 로 남김)
- `StreamUnsupportedException` (-32067)
- JSON-RPC error 응답의 `id` echo back (Phase 2 는 항상 null)
- JSON-RPC parse error / invalid params 를 `-32700` / `-32602` 코드로 처리 (현재는 Spring 기본 400)
- `historyLength`, `pushNotificationConfig`, Kafka DLQ, Micrometer metric
- Task state `input-required`, `auth-required` 수용
- `sendBlocking` / `sendAsync` 의 중복 코드 공통화 (Phase 3 에서 `sendStream` 추가 시 3중 중복이 되면 그 시점에 정리)

---

## 체크포인트 — 커밋 타임라인

| #   | 커밋 메시지                                                                 | 범위            |
| --- | --------------------------------------------------------------------------- | --------------- |
| 1   | `feat(api): add A2AException base class with JSON-RPC error codes`          | Task 1          |
| 2   | `refactor(api): migrate Agent/Kafka exceptions to A2AException base`        | Task 2          |
| 3   | `feat(api): add JSON-RPC 2.0 envelope DTOs for A2A wire format`             | Task 3          |
| 4   | `feat(api): add A2AExceptionHandler returning JSON-RPC error envelopes`     | Task 4          |
| 5   | `feat(api): add GetTaskService for A2A task polling`                        | Task 5          |
| 6   | `feat(api): add async submit mode to SendMessageService`                    | Task 6          |
| 7   | `feat(api): wrap message:send and add GET tasks/{id} in JSON-RPC envelope`  | Task 7 + 8 묶음 |
| 8   | `test(api): add AgentControllerTest cases for async submit and getTask`     | Task 9          |
| 9   | `test(api): extend e2e TaskProcessingScenarioTest for envelope and polling` | Task 10         |
| 10  | `docs(api): add Phase 2 logging fields for async submit and task polling`   | Task 11         |

총 10개 commit. Phase 1 (15 commit) 보다 간결.

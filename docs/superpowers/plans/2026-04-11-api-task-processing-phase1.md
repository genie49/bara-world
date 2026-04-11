# API Task Processing Phase 1 (블로킹 동기) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /agents/{agentName}/message:send` 블로킹 동기 모드를 end-to-end로 완성한다. 요청 → Mongo `submitted` save → Kafka 발행(ack 대기) → Redis Stream 구독 → Kafka 결과 수신 → Mongo update → EventBus publish → 대기자 깨워 A2A Task 응답.

**Architecture:** Hexagonal. 도메인 `Task` aggregate와 `TaskEventBusPort`/`TaskRepositoryPort`/`TaskPublisherPort` 3 포트. 어댑터는 Redis Stream(EventBus), MongoDB(Repository), Kafka(Publisher + ResultConsumer). Servlet은 `CompletableFuture`로 async dispatch — 대기 동안 Tomcat 스레드 해제. Redis Stream은 인스턴스 간 이벤트 브릿지로 동작 (shared Kafka consumer group).

**Tech Stack:** Kotlin, Spring Boot 3.4.4, spring-data-mongodb, spring-data-redis(Lettuce), spring-kafka, MockK/SpringMockk, JUnit 5, JDK 21 `CompletableFuture`, `ExecutorService`

**중요:**

- 커밋 메시지에 `Co-Authored-By` 트레일러를 붙이지 마라. `.husky/commit-msg` 훅이 차단한다.
- git commit 시 `--no-verify` 플래그를 사용하지 마라.
- 본 Phase는 spec `docs/superpowers/specs/2026-04-09-task-processing-design.md` 중 **시나리오 A(블로킹 동기)** 만 구현한다. 폴링(GetTaskService)/SSE(StreamMessageService, SubscribeTaskService)/JSON-RPC envelope 은 Phase 2/3로 연기한다.
- `returnImmediately` 플래그 분기, `GET /tasks/{id}`, `:subscribe`, `message:stream`, `JsonRpcRequest/Response` 랩핑은 본 Phase에서 다루지 않는다.

---

## Phase 1 파일 트리 (신규/수정 요약)

```
apps/api/src/main/kotlin/com/bara/api/
├── domain/
│   ├── model/
│   │   ├── Task.kt                       ★ 신규
│   │   ├── TaskState.kt                  ★ 신규
│   │   ├── A2AMessage.kt                 ★ 신규
│   │   ├── A2AArtifact.kt                ★ 신규
│   │   └── TaskEvent.kt                  ★ 신규
│   └── exception/
│       ├── AgentTimeoutException.kt      ★ 신규
│       └── KafkaPublishException.kt      ★ 신규
├── application/
│   ├── port/
│   │   ├── in/command/
│   │   │   └── SendMessageUseCase.kt     (수정)
│   │   └── out/
│   │       ├── TaskPublisherPort.kt      (수정 — typed payload + ack)
│   │       ├── TaskRepositoryPort.kt     ★ 신규
│   │       ├── TaskEventBusPort.kt       ★ 신규
│   │       └── TaskMessagePayload.kt     ★ 신규 (port와 같이 위치)
│   └── service/command/
│       └── SendMessageService.kt         (수정 — 블로킹 await)
├── adapter/
│   ├── in/
│   │   ├── rest/
│   │   │   ├── AgentController.kt        (수정)
│   │   │   ├── AgentDtos.kt              (수정)
│   │   │   ├── ApiExceptionHandler.kt    (수정 — 새 예외 핸들러 추가)
│   │   │   └── a2a/
│   │   │       ├── A2ATaskDto.kt         ★ 신규
│   │   │       ├── A2ATaskStatusDto.kt   ★ 신규
│   │   │       ├── A2AMessageDto.kt      ★ 신규
│   │   │       ├── A2AArtifactDto.kt     ★ 신규
│   │   │       └── A2ATaskMapper.kt      ★ 신규
│   │   └── kafka/
│   │       └── ResultConsumerAdapter.kt  ★ 신규
│   └── out/
│       ├── persistence/
│       │   ├── TaskDocument.kt           ★ 신규
│       │   ├── TaskMongoDataRepository.kt ★ 신규
│       │   └── TaskMongoRepository.kt    ★ 신규
│       ├── kafka/
│       │   └── TaskKafkaPublisher.kt     (수정 — ack 대기 + typed)
│       └── redis/
│           ├── TaskEventJson.kt          ★ 신규
│           ├── RedisStreamTaskEventBus.kt ★ 신규
│           └── EventBusPumpThread.kt     ★ 신규
└── config/
    ├── TaskProperties.kt                 ★ 신규
    └── KafkaConsumerConfig.kt            ★ 신규

apps/api/src/main/resources/
└── application.yml                       (수정 — consumer group, manual commit, bara.api.task)

apps/api/src/test/kotlin/com/bara/api/
├── domain/
│   └── TaskStateTest.kt                  ★ 신규
├── application/service/command/
│   └── SendMessageServiceTest.kt         (수정)
├── adapter/
│   ├── in/rest/
│   │   ├── AgentControllerTest.kt        (수정)
│   │   └── a2a/A2ATaskMapperTest.kt      ★ 신규
│   ├── in/kafka/
│   │   └── ResultConsumerAdapterTest.kt  ★ 신규
│   └── out/
│       ├── persistence/TaskMongoRepositoryTest.kt ★ 신규 (@DataMongoTest)
│       ├── kafka/TaskKafkaPublisherTest.kt        (수정)
│       └── redis/RedisStreamTaskEventBusTest.kt   ★ 신규 (@DataRedisTest)

docs/guides/logging/flows/
└── api-task-processing.md                ★ 신규
```

**정리 원칙:**

- `Task` 도메인은 `Agent` 도메인과 완전 분리된 별도 aggregate.
- `TaskEventBusPort`는 Redis에 종속되지 않는다. 테스트는 단위 테스트(fake)로 가능.
- A2A wire DTO(`adapter/in/rest/a2a/`)와 도메인 모델(`domain/model/`) 완전 분리. 매퍼가 경계 담당.
- 기존 `SendMessageApiRequest`는 구조 유지(입력 포맷 breaking change 없음). 응답만 `A2ATaskDto`로 교체.

---

## 실행 순서

Task 순서는 의존성 역방향으로 설계. 도메인 → 포트 → 어댑터 → 서비스 → 컨트롤러 → 설정 → 문서.

각 Task는 독립적으로 커밋 가능하며, Task 14까지 완료 후 `./gradlew :apps:api:test :apps:api:build` 가 녹색이어야 한다.

---

### Task 1: 도메인 모델 — `TaskState` + `A2AMessage` + `A2AArtifact`

**Files:**

- Create: `apps/api/src/main/kotlin/com/bara/api/domain/model/TaskState.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/domain/model/A2AMessage.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/domain/model/A2AArtifact.kt`
- Create: `apps/api/src/test/kotlin/com/bara/api/domain/TaskStateTest.kt`

- [ ] **Step 1: `TaskStateTest.kt` failing 테스트 작성**

```kotlin
package com.bara.api.domain

import com.bara.api.domain.model.TaskState
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskStateTest {

    @Test
    fun `SUBMITTED, WORKING은 비터미널`() {
        assertFalse(TaskState.SUBMITTED.isTerminal)
        assertFalse(TaskState.WORKING.isTerminal)
    }

    @Test
    fun `COMPLETED, FAILED, CANCELED, REJECTED는 터미널`() {
        assertTrue(TaskState.COMPLETED.isTerminal)
        assertTrue(TaskState.FAILED.isTerminal)
        assertTrue(TaskState.CANCELED.isTerminal)
        assertTrue(TaskState.REJECTED.isTerminal)
    }
}
```

- [ ] **Step 2: 테스트 실행하여 실패 확인**

Run: `./gradlew :apps:api:test --tests "com.bara.api.domain.TaskStateTest"`
Expected: FAIL — `TaskState` 없음

- [ ] **Step 3: `TaskState.kt` 작성**

```kotlin
package com.bara.api.domain.model

enum class TaskState {
    SUBMITTED,
    WORKING,
    COMPLETED,
    FAILED,
    CANCELED,
    REJECTED;

    val isTerminal: Boolean
        get() = this in TERMINAL_STATES

    companion object {
        private val TERMINAL_STATES = setOf(COMPLETED, FAILED, CANCELED, REJECTED)
    }
}
```

- [ ] **Step 4: `A2AMessage.kt` 작성**

```kotlin
package com.bara.api.domain.model

data class A2AMessage(
    val messageId: String,
    val role: String,          // "user" | "agent"
    val parts: List<A2APart>,
)

data class A2APart(
    val kind: String,           // "text"
    val text: String,
)
```

- [ ] **Step 5: `A2AArtifact.kt` 작성**

```kotlin
package com.bara.api.domain.model

data class A2AArtifact(
    val artifactId: String,
    val name: String? = null,
    val parts: List<A2APart> = emptyList(),
)
```

- [ ] **Step 6: 테스트 실행하여 통과 확인**

Run: `./gradlew :apps:api:test --tests "com.bara.api.domain.TaskStateTest"`
Expected: PASS (2 tests)

- [ ] **Step 7: 커밋**

```bash
git add apps/api/src/main/kotlin/com/bara/api/domain/model/TaskState.kt \
        apps/api/src/main/kotlin/com/bara/api/domain/model/A2AMessage.kt \
        apps/api/src/main/kotlin/com/bara/api/domain/model/A2AArtifact.kt \
        apps/api/src/test/kotlin/com/bara/api/domain/TaskStateTest.kt
git commit -m "feat(api): add Task domain state and A2A message models"
```

---

### Task 2: 도메인 모델 — `Task` + `TaskEvent`

**Files:**

- Create: `apps/api/src/main/kotlin/com/bara/api/domain/model/Task.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/domain/model/TaskEvent.kt`

순수 데이터 클래스. 별도 단위 테스트는 불필요 (상태 전환 로직은 `TaskState`에 이미 있음, Task 자체는 불변 데이터 컨테이너).

- [ ] **Step 1: `Task.kt` 작성**

```kotlin
package com.bara.api.domain.model

import java.time.Instant

data class Task(
    val id: String,                    // UUID v4
    val agentId: String,
    val agentName: String,
    val userId: String,
    val contextId: String,
    val state: TaskState,
    val inputMessage: A2AMessage,
    val statusMessage: A2AMessage? = null,
    val artifacts: List<A2AArtifact> = emptyList(),
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val requestId: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant? = null,
    val expiredAt: Instant,
)
```

- [ ] **Step 2: `TaskEvent.kt` 작성**

```kotlin
package com.bara.api.domain.model

import java.time.Instant

data class TaskEvent(
    val taskId: String,
    val contextId: String,
    val state: TaskState,
    val statusMessage: A2AMessage? = null,
    val artifact: A2AArtifact? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val final: Boolean,
    val timestamp: Instant,
) {
    companion object {
        fun of(task: Task): TaskEvent = TaskEvent(
            taskId = task.id,
            contextId = task.contextId,
            state = task.state,
            statusMessage = task.statusMessage,
            artifact = task.artifacts.firstOrNull(),
            errorCode = task.errorCode,
            errorMessage = task.errorMessage,
            final = task.state.isTerminal,
            timestamp = task.updatedAt,
        )
    }
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew :apps:api:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add apps/api/src/main/kotlin/com/bara/api/domain/model/Task.kt \
        apps/api/src/main/kotlin/com/bara/api/domain/model/TaskEvent.kt
git commit -m "feat(api): add Task aggregate and TaskEvent value object"
```

---

### Task 3: 도메인 예외 — `AgentTimeoutException` + `KafkaPublishException`

**Files:**

- Create: `apps/api/src/main/kotlin/com/bara/api/domain/exception/AgentTimeoutException.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/domain/exception/KafkaPublishException.kt`

- [ ] **Step 1: `AgentTimeoutException.kt` 작성**

```kotlin
package com.bara.api.domain.exception

class AgentTimeoutException(
    message: String = "Agent did not respond within timeout",
) : RuntimeException(message)
```

- [ ] **Step 2: `KafkaPublishException.kt` 작성**

```kotlin
package com.bara.api.domain.exception

class KafkaPublishException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew :apps:api:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add apps/api/src/main/kotlin/com/bara/api/domain/exception/AgentTimeoutException.kt \
        apps/api/src/main/kotlin/com/bara/api/domain/exception/KafkaPublishException.kt
git commit -m "feat(api): add AgentTimeout and KafkaPublish exceptions"
```

---

### Task 4: 포트 — `TaskRepositoryPort` + `TaskEventBusPort` + typed `TaskPublisherPort`

**Files:**

- Create: `apps/api/src/main/kotlin/com/bara/api/application/port/out/TaskRepositoryPort.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/application/port/out/TaskEventBusPort.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/application/port/out/TaskMessagePayload.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/application/port/out/TaskPublisherPort.kt`

본 Task는 시그니처만 정의. 구현체 수정은 후속 Task에서 담당. `TaskPublisherPort` 시그니처 변경으로 기존 `TaskKafkaPublisher`/`SendMessageService`가 컴파일 깨짐 → **본 Task 마지막에 임시 어댑터 구현으로 컴파일만 살리고 다음 Task에서 정식 교체**한다.

- [ ] **Step 1: `TaskMessagePayload.kt` 작성**

```kotlin
package com.bara.api.application.port.out

import com.bara.api.domain.model.A2AMessage

data class TaskMessagePayload(
    val taskId: String,
    val contextId: String,
    val userId: String,
    val requestId: String,
    val resultTopic: String,            // "results.api"
    val allowedAgents: List<String>,    // 현재는 빈 리스트
    val message: A2AMessage,
)
```

- [ ] **Step 2: `TaskPublisherPort.kt` 전체 교체**

```kotlin
package com.bara.api.application.port.out

interface TaskPublisherPort {
    /**
     * Kafka tasks.{agentId} 발행. ack 대기 후 반환.
     * 실패 시 KafkaPublishException.
     */
    fun publish(agentId: String, payload: TaskMessagePayload)
}
```

- [ ] **Step 3: `TaskRepositoryPort.kt` 작성**

```kotlin
package com.bara.api.application.port.out

import com.bara.api.domain.model.A2AArtifact
import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskState
import java.time.Instant

interface TaskRepositoryPort {
    fun save(task: Task): Task

    fun findById(id: String): Task?

    fun updateState(
        id: String,
        state: TaskState,
        statusMessage: A2AMessage?,
        artifacts: List<A2AArtifact>,
        errorCode: String?,
        errorMessage: String?,
        updatedAt: Instant,
        completedAt: Instant?,
        expiredAt: Instant?,
    ): Boolean
}
```

- [ ] **Step 4: `TaskEventBusPort.kt` 작성 (+`Subscription`)**

```kotlin
package com.bara.api.application.port.out

import com.bara.api.domain.model.TaskEvent
import java.time.Duration
import java.util.concurrent.CompletableFuture

interface TaskEventBusPort {

    /** 이벤트 발행. 반환: 스트림 entry ID (Redis Stream `ms-seq`). */
    fun publish(taskId: String, event: TaskEvent): String

    /**
     * 스트림 구독.
     * fromStreamId:
     *   "$"        — 새 이벤트만
     *   "0"        — 처음부터 (backfill + tailing)
     *   "<id>"     — 특정 offset 이후 (Last-Event-ID 재연결)
     * listener는 백그라운드 스레드에서 호출된다.
     */
    fun subscribe(
        taskId: String,
        fromStreamId: String,
        listener: (TaskEvent) -> Unit,
    ): Subscription

    /**
     * 블로킹 대기 편의 API. 내부 구현:
     *   subscribe(taskId, "0") { event -> if (event.final) future.complete(event) }
     *   future.orTimeout(timeout).whenComplete { _, _ -> subscription.close() }
     */
    fun await(taskId: String, timeout: Duration): CompletableFuture<TaskEvent>

    /** 터미널 이후 grace period 후 스트림 DEL. 구현은 TTL `EXPIRE` 권장. */
    fun close(taskId: String)
}

interface Subscription : AutoCloseable {
    override fun close()
}
```

- [ ] **Step 5: 기존 `TaskKafkaPublisher` 임시 수정 — 컴파일 통과용 stub**

기존 `Map<String, Any?>` 시그니처가 사라지므로 `TaskKafkaPublisher.publish`를 새 시그니처에 맞게 빈 구현으로 교체 (Task 7에서 정식 구현). 기존 `SendMessageService.sendMessage`의 publisher 호출도 Task 12에서 rewrite될 것이므로, 이 Task에서는 해당 호출 라인만 일시 주석 처리 대신 새 시그니처로 빌드만 맞춘다.

`apps/api/src/main/kotlin/com/bara/api/adapter/out/kafka/TaskKafkaPublisher.kt` 전체 교체:

```kotlin
package com.bara.api.adapter.out.kafka

import com.bara.api.application.port.out.TaskMessagePayload
import com.bara.api.application.port.out.TaskPublisherPort
import com.bara.api.domain.exception.KafkaPublishException
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Component
class TaskKafkaPublisher(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) : TaskPublisherPort {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun publish(agentId: String, payload: TaskMessagePayload) {
        val topic = "tasks.$agentId"
        val wire = mapOf(
            "task_id" to payload.taskId,
            "context_id" to payload.contextId,
            "user_id" to payload.userId,
            "request_id" to payload.requestId,
            "result_topic" to payload.resultTopic,
            "allowed_agents" to payload.allowedAgents,
            "message" to mapOf(
                "message_id" to payload.message.messageId,
                "role" to payload.message.role,
                "parts" to payload.message.parts.map {
                    mapOf("kind" to it.kind, "text" to it.text)
                },
            ),
        )
        val json = objectMapper.writeValueAsString(wire)
        try {
            kafkaTemplate.send(topic, json).get(KAFKA_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            logger.info("Task published topic={} taskId={}", topic, payload.taskId)
        } catch (e: TimeoutException) {
            throw KafkaPublishException("Kafka ack timeout topic=$topic", e)
        } catch (e: ExecutionException) {
            throw KafkaPublishException("Kafka publish failed topic=$topic", e.cause ?: e)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw KafkaPublishException("Kafka publish interrupted topic=$topic", e)
        }
    }

    companion object {
        private const val KAFKA_ACK_TIMEOUT_SECONDS = 5L
    }
}
```

> 주: 타임아웃 상수 `5L`은 Task 13에서 `TaskProperties`로 주입 전환한다. 본 Task에서는 하드코딩.

- [ ] **Step 6: 기존 `SendMessageService` 임시 수정 — 컴파일 통과용**

`apps/api/src/main/kotlin/com/bara/api/application/service/command/SendMessageService.kt` 의 기존 `taskPublisherPort.publish(agentId, taskMessage)` 호출 블록을 새 시그니처에 맞게 교체. **본 Task에서는 블로킹 await 로직을 추가하지 않는다** — 단지 빌드를 살리는 것이 목표. Task 12에서 전체 rewrite.

기존 `sendMessage` 바디에서 `val taskMessage = mapOf(...)` 와 `taskPublisherPort.publish(agentId, taskMessage)` 블록을 아래로 교체:

```kotlin
        val payload = com.bara.api.application.port.out.TaskMessagePayload(
            taskId = taskId,
            contextId = request.contextId ?: java.util.UUID.randomUUID().toString(),
            userId = userId,
            requestId = requestId,
            resultTopic = "results.api",
            allowedAgents = emptyList(),
            message = com.bara.api.domain.model.A2AMessage(
                messageId = request.messageId,
                role = "user",
                parts = listOf(
                    com.bara.api.domain.model.A2APart(kind = "text", text = request.text),
                ),
            ),
        )
        taskPublisherPort.publish(agentId, payload)
```

- [ ] **Step 7: 기존 `TaskKafkaPublisherTest` 임시 수정**

`apps/api/src/test/kotlin/com/bara/api/adapter/out/kafka/TaskKafkaPublisherTest.kt` 전체 교체:

```kotlin
package com.bara.api.adapter.out.kafka

import com.bara.api.application.port.out.TaskMessagePayload
import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.A2APart
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.util.concurrent.CompletableFuture
import kotlin.test.assertTrue

class TaskKafkaPublisherTest {

    private val kafkaTemplate = mockk<KafkaTemplate<String, String>>()
    private val publisher = TaskKafkaPublisher(kafkaTemplate, ObjectMapper())

    @Test
    fun `publish는 tasks dot agentId 토픽에 JSON을 발행하고 ack를 대기한다`() {
        val topicSlot = slot<String>()
        val valueSlot = slot<String>()
        every { kafkaTemplate.send(capture(topicSlot), capture(valueSlot)) } returns
            CompletableFuture.completedFuture(mockk<SendResult<String, String>>(relaxed = true))

        val payload = TaskMessagePayload(
            taskId = "t-1",
            contextId = "c-1",
            userId = "u-1",
            requestId = "r-1",
            resultTopic = "results.api",
            allowedAgents = emptyList(),
            message = A2AMessage(
                messageId = "m-1",
                role = "user",
                parts = listOf(A2APart(kind = "text", text = "hi")),
            ),
        )

        publisher.publish("agent-001", payload)

        verify { kafkaTemplate.send("tasks.agent-001", any()) }
        assertTrue(valueSlot.captured.contains("\"task_id\":\"t-1\""))
        assertTrue(valueSlot.captured.contains("\"parts\":[{\"kind\":\"text\",\"text\":\"hi\"}]"))
    }
}
```

- [ ] **Step 8: 기존 `SendMessageServiceTest` 임시 수정**

`apps/api/src/test/kotlin/com/bara/api/application/service/command/SendMessageServiceTest.kt` 의 `justRun { taskPublisherPort.publish(eq("agent-001"), any()) }` 는 그대로 동작(시그니처 `Any` 매처). 변경 불필요. 다만 테스트 프레임워크가 매처 해석을 위해 `any<TaskMessagePayload>()` 로 명시하는 게 안전:

두 테스트 케이스의 `any()` → `any<com.bara.api.application.port.out.TaskMessagePayload>()` 로 교체.

- [ ] **Step 9: 빌드 + 테스트 통과 확인**

Run: `./gradlew :apps:api:test`
Expected: BUILD SUCCESSFUL, 기존 테스트 전부 green

- [ ] **Step 10: 커밋**

```bash
git add apps/api/src/main/kotlin/com/bara/api/application/port/out/ \
        apps/api/src/main/kotlin/com/bara/api/adapter/out/kafka/TaskKafkaPublisher.kt \
        apps/api/src/main/kotlin/com/bara/api/application/service/command/SendMessageService.kt \
        apps/api/src/test/kotlin/com/bara/api/adapter/out/kafka/TaskKafkaPublisherTest.kt \
        apps/api/src/test/kotlin/com/bara/api/application/service/command/SendMessageServiceTest.kt
git commit -m "feat(api): introduce TaskRepositoryPort, TaskEventBusPort, typed TaskPublisherPort"
```

---

### Task 5: `TaskProperties` 설정

**Files:**

- Create: `apps/api/src/main/kotlin/com/bara/api/config/TaskProperties.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/BaraApiApplication.kt` (ConfigurationPropertiesScan 활성화 여부 확인)

- [ ] **Step 1: `TaskProperties.kt` 작성**

```kotlin
package com.bara.api.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "bara.api.task")
data class TaskProperties(
    val blockTimeoutSeconds: Long = 30,
    val kafkaPublishTimeoutSeconds: Long = 5,
    val streamGracePeriodSeconds: Long = 60,
    val mongoTtlDays: Long = 7,
)
```

- [ ] **Step 2: `BaraApiApplication.kt` 확인 및 수정**

현재 파일을 읽어 `@ConfigurationPropertiesScan` 또는 `@EnableConfigurationProperties(TaskProperties::class)` 가 없으면 추가.

`apps/api/src/main/kotlin/com/bara/api/BaraApiApplication.kt` 를 읽고, `@SpringBootApplication` 바로 아래에 다음을 추가:

```kotlin
@org.springframework.boot.context.properties.ConfigurationPropertiesScan("com.bara.api.config")
```

(이미 있으면 skip)

- [ ] **Step 3: 빌드 확인**

Run: `./gradlew :apps:api:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
git add apps/api/src/main/kotlin/com/bara/api/config/TaskProperties.kt \
        apps/api/src/main/kotlin/com/bara/api/BaraApiApplication.kt
git commit -m "feat(api): add TaskProperties for task processing timeouts"
```

---

### Task 6: MongoDB 어댑터 — `TaskDocument` + `TaskMongoDataRepository` + `TaskMongoRepository`

**Files:**

- Create: `apps/api/src/main/kotlin/com/bara/api/adapter/out/persistence/TaskDocument.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/adapter/out/persistence/TaskMongoDataRepository.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/adapter/out/persistence/TaskMongoRepository.kt`
- Create: `apps/api/src/test/kotlin/com/bara/api/adapter/out/persistence/TaskMongoRepositoryTest.kt`

**테스트 전략:** `@DataMongoTest` + Flapdoodle embedded MongoDB (기존 `AgentMongoDataRepository`가 쓰는 방식 확인 필요. 만약 기존 테스트가 없거나 slice test가 아니라면 이 Task에서는 `mockk` 단위 테스트로 `TaskMongoRepository`만 검증).

> **확인 필요**: 아래 Step 1 직전에 기존 MongoDB 테스트 방식을 한 번 점검하고, Flapdoodle 의존성이 이미 빌드에 있으면 `@DataMongoTest`로 진행, 없으면 `mockk` 단위 테스트로 대체한다. 현재 `apps/api/build.gradle.kts` 에는 `de.flapdoodle.embed.mongo` 의존성이 없음 → **mockk 단위 테스트로 간다**.

- [ ] **Step 1: `TaskMongoRepositoryTest.kt` failing 테스트 작성 (mockk)**

```kotlin
package com.bara.api.adapter.out.persistence

import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.A2APart
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskState
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TaskMongoRepositoryTest {

    private val dataRepository = mockk<TaskMongoDataRepository>()
    private val repository = TaskMongoRepository(dataRepository)

    private val now = Instant.parse("2026-04-11T00:00:00Z")

    private val task = Task(
        id = "t-1",
        agentId = "a-1",
        agentName = "my-agent",
        userId = "u-1",
        contextId = "c-1",
        state = TaskState.SUBMITTED,
        inputMessage = A2AMessage("m-1", "user", listOf(A2APart("text", "hi"))),
        requestId = "r-1",
        createdAt = now,
        updatedAt = now,
        expiredAt = now.plusSeconds(7 * 24 * 3600),
    )

    @Test
    fun `save는 Document로 변환해 저장하고 Domain으로 반환한다`() {
        val docSlot = slot<TaskDocument>()
        every { dataRepository.save(capture(docSlot)) } answers { docSlot.captured }

        val saved = repository.save(task)

        assertEquals("t-1", saved.id)
        assertEquals(TaskState.SUBMITTED, saved.state)
        verify { dataRepository.save(any<TaskDocument>()) }
    }

    @Test
    fun `findById는 Domain으로 복원한다`() {
        every { dataRepository.findById("t-1") } returns
            Optional.of(TaskDocument.fromDomain(task))

        val found = repository.findById("t-1")
        assertNotNull(found)
        assertEquals("t-1", found.id)
    }

    @Test
    fun `findById 미존재 시 null 반환`() {
        every { dataRepository.findById("missing") } returns Optional.empty()
        val result = repository.findById("missing")
        assertEquals(null, result)
    }
}
```

- [ ] **Step 2: 테스트 실행하여 실패 확인**

Run: `./gradlew :apps:api:test --tests "com.bara.api.adapter.out.persistence.TaskMongoRepositoryTest"`
Expected: FAIL — `TaskDocument`, `TaskMongoDataRepository`, `TaskMongoRepository` 없음

- [ ] **Step 3: `TaskDocument.kt` 작성**

```kotlin
package com.bara.api.adapter.out.persistence

import com.bara.api.domain.model.A2AArtifact
import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.A2APart
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskState
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document(collection = "tasks")
data class TaskDocument(
    @Id val id: String,
    val agentId: String,
    val agentName: String,
    val userId: String,
    val contextId: String,
    val state: String,
    val inputMessage: A2AMessageDoc,
    val statusMessage: A2AMessageDoc? = null,
    val artifacts: List<A2AArtifactDoc> = emptyList(),
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val requestId: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant? = null,
    @Indexed(expireAfterSeconds = 0)
    val expiredAt: Instant,
) {
    fun toDomain(): Task = Task(
        id = id,
        agentId = agentId,
        agentName = agentName,
        userId = userId,
        contextId = contextId,
        state = TaskState.valueOf(state),
        inputMessage = inputMessage.toDomain(),
        statusMessage = statusMessage?.toDomain(),
        artifacts = artifacts.map { it.toDomain() },
        errorCode = errorCode,
        errorMessage = errorMessage,
        requestId = requestId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
        expiredAt = expiredAt,
    )

    companion object {
        fun fromDomain(t: Task): TaskDocument = TaskDocument(
            id = t.id,
            agentId = t.agentId,
            agentName = t.agentName,
            userId = t.userId,
            contextId = t.contextId,
            state = t.state.name,
            inputMessage = A2AMessageDoc.fromDomain(t.inputMessage),
            statusMessage = t.statusMessage?.let(A2AMessageDoc::fromDomain),
            artifacts = t.artifacts.map(A2AArtifactDoc::fromDomain),
            errorCode = t.errorCode,
            errorMessage = t.errorMessage,
            requestId = t.requestId,
            createdAt = t.createdAt,
            updatedAt = t.updatedAt,
            completedAt = t.completedAt,
            expiredAt = t.expiredAt,
        )
    }
}

data class A2AMessageDoc(
    val messageId: String,
    val role: String,
    val parts: List<A2APartDoc>,
) {
    fun toDomain(): A2AMessage = A2AMessage(
        messageId = messageId,
        role = role,
        parts = parts.map { it.toDomain() },
    )

    companion object {
        fun fromDomain(m: A2AMessage): A2AMessageDoc = A2AMessageDoc(
            messageId = m.messageId,
            role = m.role,
            parts = m.parts.map(A2APartDoc::fromDomain),
        )
    }
}

data class A2APartDoc(val kind: String, val text: String) {
    fun toDomain(): A2APart = A2APart(kind = kind, text = text)

    companion object {
        fun fromDomain(p: A2APart): A2APartDoc = A2APartDoc(kind = p.kind, text = p.text)
    }
}

data class A2AArtifactDoc(
    val artifactId: String,
    val name: String? = null,
    val parts: List<A2APartDoc> = emptyList(),
) {
    fun toDomain(): A2AArtifact = A2AArtifact(
        artifactId = artifactId,
        name = name,
        parts = parts.map { it.toDomain() },
    )

    companion object {
        fun fromDomain(a: A2AArtifact): A2AArtifactDoc = A2AArtifactDoc(
            artifactId = a.artifactId,
            name = a.name,
            parts = a.parts.map(A2APartDoc::fromDomain),
        )
    }
}
```

- [ ] **Step 4: `TaskMongoDataRepository.kt` 작성**

```kotlin
package com.bara.api.adapter.out.persistence

import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface TaskMongoDataRepository : MongoRepository<TaskDocument, String>
```

- [ ] **Step 5: `TaskMongoRepository.kt` 작성**

```kotlin
package com.bara.api.adapter.out.persistence

import com.bara.api.application.port.out.TaskRepositoryPort
import com.bara.api.domain.model.A2AArtifact
import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskState
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class TaskMongoRepository(
    private val dataRepository: TaskMongoDataRepository,
    private val mongoTemplate: MongoTemplate? = null,
) : TaskRepositoryPort {

    override fun save(task: Task): Task =
        dataRepository.save(TaskDocument.fromDomain(task)).toDomain()

    override fun findById(id: String): Task? =
        dataRepository.findById(id).orElse(null)?.toDomain()

    override fun updateState(
        id: String,
        state: TaskState,
        statusMessage: A2AMessage?,
        artifacts: List<A2AArtifact>,
        errorCode: String?,
        errorMessage: String?,
        updatedAt: Instant,
        completedAt: Instant?,
        expiredAt: Instant?,
    ): Boolean {
        val template = mongoTemplate
            ?: return fallbackUpdate(id, state, statusMessage, artifacts, errorCode, errorMessage, updatedAt, completedAt, expiredAt)
        val update = Update()
            .set("state", state.name)
            .set("updatedAt", updatedAt)
        statusMessage?.let { update.set("statusMessage", A2AMessageDoc.fromDomain(it)) }
        if (artifacts.isNotEmpty()) update.set("artifacts", artifacts.map(A2AArtifactDoc::fromDomain))
        errorCode?.let { update.set("errorCode", it) }
        errorMessage?.let { update.set("errorMessage", it) }
        completedAt?.let { update.set("completedAt", it) }
        expiredAt?.let { update.set("expiredAt", it) }

        val result = template.updateFirst(
            Query(Criteria.where("_id").`is`(id)),
            update,
            TaskDocument::class.java,
        )
        return result.modifiedCount > 0
    }

    private fun fallbackUpdate(
        id: String,
        state: TaskState,
        statusMessage: A2AMessage?,
        artifacts: List<A2AArtifact>,
        errorCode: String?,
        errorMessage: String?,
        updatedAt: Instant,
        completedAt: Instant?,
        expiredAt: Instant?,
    ): Boolean {
        val existing = dataRepository.findById(id).orElse(null) ?: return false
        val updated = existing.copy(
            state = state.name,
            statusMessage = statusMessage?.let(A2AMessageDoc::fromDomain) ?: existing.statusMessage,
            artifacts = if (artifacts.isNotEmpty()) artifacts.map(A2AArtifactDoc::fromDomain) else existing.artifacts,
            errorCode = errorCode ?: existing.errorCode,
            errorMessage = errorMessage ?: existing.errorMessage,
            updatedAt = updatedAt,
            completedAt = completedAt ?: existing.completedAt,
            expiredAt = expiredAt ?: existing.expiredAt,
        )
        dataRepository.save(updated)
        return true
    }
}
```

> 주: 프로덕션은 `MongoTemplate`의 `updateFirst`가 멱등적 `$set`을 수행. 단위 테스트는 `MongoTemplate`이 null이므로 `fallbackUpdate`로 `save`를 이용. 이 설계로 순수 mockk 테스트가 가능.

- [ ] **Step 6: 테스트 실행하여 통과 확인**

Run: `./gradlew :apps:api:test --tests "com.bara.api.adapter.out.persistence.TaskMongoRepositoryTest"`
Expected: PASS (3 tests)

- [ ] **Step 7: 커밋**

```bash
git add apps/api/src/main/kotlin/com/bara/api/adapter/out/persistence/TaskDocument.kt \
        apps/api/src/main/kotlin/com/bara/api/adapter/out/persistence/TaskMongoDataRepository.kt \
        apps/api/src/main/kotlin/com/bara/api/adapter/out/persistence/TaskMongoRepository.kt \
        apps/api/src/test/kotlin/com/bara/api/adapter/out/persistence/TaskMongoRepositoryTest.kt
git commit -m "feat(api): add Task MongoDB adapter with TTL index"
```

---

### Task 7: Redis Stream 어댑터 — `TaskEventJson` + `RedisStreamTaskEventBus` publish/close

**Files:**

- Create: `apps/api/src/main/kotlin/com/bara/api/adapter/out/redis/TaskEventJson.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/adapter/out/redis/RedisStreamTaskEventBus.kt`
- Create: `apps/api/src/test/kotlin/com/bara/api/adapter/out/redis/RedisStreamTaskEventBusTest.kt`

본 Task는 `publish`/`close` 만 구현. `subscribe`/`await` 는 Task 8에서 pump thread와 함께 구현.

- [ ] **Step 1: `RedisStreamTaskEventBusTest.kt` failing 테스트 작성 (mockk 단위)**

```kotlin
package com.bara.api.adapter.out.redis

import com.bara.api.config.TaskProperties
import com.bara.api.domain.model.TaskEvent
import com.bara.api.domain.model.TaskState
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.connection.stream.RecordId
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals

class RedisStreamTaskEventBusTest {

    private val redisTemplate = mockk<StringRedisTemplate>()
    private val streamOps = mockk<StreamOperations<String, Any, Any>>()
    private val properties = TaskProperties()
    private val objectMapper = ObjectMapper().registerKotlinModule()

    private fun newBus() = RedisStreamTaskEventBus(redisTemplate, objectMapper, properties)

    @Test
    fun `publish는 stream task 키에 XADD 한다`() {
        every { redisTemplate.opsForStream<Any, Any>() } returns streamOps
        every { streamOps.add(any<MapRecord<String, Any, Any>>()) } returns
            RecordId.of(1712665200000L, 0L)

        val event = TaskEvent(
            taskId = "t-1",
            contextId = "c-1",
            state = TaskState.SUBMITTED,
            statusMessage = null,
            final = false,
            timestamp = Instant.parse("2026-04-11T00:00:00Z"),
        )

        val id = newBus().publish("t-1", event)

        assertEquals("1712665200000-0", id)
        verify {
            streamOps.add(match<MapRecord<String, Any, Any>> { rec ->
                rec.stream == "stream:task:t-1" && rec.value.containsKey("event")
            })
        }
    }

    @Test
    fun `close는 stream task 키에 grace period로 expire 를 건다`() {
        every { redisTemplate.expire("stream:task:t-1", Duration.ofSeconds(60)) } returns true

        newBus().close("t-1")

        verify { redisTemplate.expire("stream:task:t-1", Duration.ofSeconds(60)) }
    }
}
```

- [ ] **Step 2: `TaskEventJson.kt` 작성**

```kotlin
package com.bara.api.adapter.out.redis

import com.bara.api.domain.model.TaskEvent
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant

/**
 * Redis Stream 엔트리 payload 직렬화. 단일 "event" 필드에 전체 JSON 저장.
 */
object TaskEventJson {

    fun serialize(mapper: ObjectMapper, event: TaskEvent): String =
        mapper.writeValueAsString(WireFormat.from(event))

    fun deserialize(mapper: ObjectMapper, json: String): TaskEvent =
        mapper.readValue(json, WireFormat::class.java).toDomain()

    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class WireFormat(
        val taskId: String,
        val contextId: String,
        val state: String,
        val statusMessage: com.bara.api.domain.model.A2AMessage? = null,
        val artifact: com.bara.api.domain.model.A2AArtifact? = null,
        val errorCode: String? = null,
        val errorMessage: String? = null,
        val final: Boolean,
        val timestamp: String,
    ) {
        fun toDomain(): TaskEvent = TaskEvent(
            taskId = taskId,
            contextId = contextId,
            state = com.bara.api.domain.model.TaskState.valueOf(state.uppercase().replace('-', '_')),
            statusMessage = statusMessage,
            artifact = artifact,
            errorCode = errorCode,
            errorMessage = errorMessage,
            final = final,
            timestamp = Instant.parse(timestamp),
        )

        companion object {
            fun from(e: TaskEvent): WireFormat = WireFormat(
                taskId = e.taskId,
                contextId = e.contextId,
                state = e.state.name.lowercase(),
                statusMessage = e.statusMessage,
                artifact = e.artifact,
                errorCode = e.errorCode,
                errorMessage = e.errorMessage,
                final = e.final,
                timestamp = e.timestamp.toString(),
            )
        }
    }
}
```

- [ ] **Step 3: `RedisStreamTaskEventBus.kt` 초기 골격 작성 (publish + close만)**

```kotlin
package com.bara.api.adapter.out.redis

import com.bara.api.application.port.out.Subscription
import com.bara.api.application.port.out.TaskEventBusPort
import com.bara.api.config.TaskProperties
import com.bara.api.domain.model.TaskEvent
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.CompletableFuture

@Component
class RedisStreamTaskEventBus(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val properties: TaskProperties,
) : TaskEventBusPort {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun publish(taskId: String, event: TaskEvent): String {
        val key = streamKey(taskId)
        val json = TaskEventJson.serialize(objectMapper, event)
        val record = StreamRecords.newRecord()
            .`in`(key)
            .ofMap(mapOf<Any, Any>("event" to json))
        val recordId = redisTemplate.opsForStream<Any, Any>().add(record)
            ?: error("Redis XADD returned null recordId for key=$key")
        return recordId.toString()
    }

    override fun subscribe(
        taskId: String,
        fromStreamId: String,
        listener: (TaskEvent) -> Unit,
    ): Subscription {
        TODO("Task 8에서 구현 — pump thread + fan-out")
    }

    override fun await(taskId: String, timeout: Duration): CompletableFuture<TaskEvent> {
        TODO("Task 8에서 구현")
    }

    override fun close(taskId: String) {
        val key = streamKey(taskId)
        val grace = Duration.ofSeconds(properties.streamGracePeriodSeconds)
        redisTemplate.expire(key, grace)
        logger.debug("Scheduled stream close key={} after={}s", key, grace.seconds)
    }

    private fun streamKey(taskId: String): String = "stream:task:$taskId"
}
```

- [ ] **Step 4: publish/close 테스트 통과 확인**

Run: `./gradlew :apps:api:test --tests "com.bara.api.adapter.out.redis.RedisStreamTaskEventBusTest"`
Expected: PASS (2 tests)

- [ ] **Step 5: 커밋**

```bash
git add apps/api/src/main/kotlin/com/bara/api/adapter/out/redis/TaskEventJson.kt \
        apps/api/src/main/kotlin/com/bara/api/adapter/out/redis/RedisStreamTaskEventBus.kt \
        apps/api/src/test/kotlin/com/bara/api/adapter/out/redis/RedisStreamTaskEventBusTest.kt
git commit -m "feat(api): add Redis Stream event bus publish/close"
```

---

### Task 8: Redis Stream pump 스레드 — `EventBusPumpThread` + `subscribe`/`await`

**Files:**

- Create: `apps/api/src/main/kotlin/com/bara/api/adapter/out/redis/EventBusPumpThread.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/adapter/out/redis/RedisStreamTaskEventBus.kt`
- Modify: `apps/api/src/test/kotlin/com/bara/api/adapter/out/redis/RedisStreamTaskEventBusTest.kt`

**전략**: `XREAD BLOCK`을 직접 루프돌리기보다, Spring Data Redis Stream 의 리스너가 아닌 **backfill + 짧은 poll** 방식으로 구현한다. 이유:

1. 블로킹 `await` 의 주 사용처는 **backfill 이벤트에서 final 을 발견하는 것**. ResultConsumer가 Mongo update 직후 EventBus publish 하므로, await 등록 시점에 이미 Stream 에 final 이벤트가 와 있을 가능성이 높다.
2. backfill 단일 `XREAD COUNT N STREAMS key 0` 호출로 모든 과거 이벤트를 즉시 받음 → final 있으면 future 즉시 complete → subscription close.
3. final 이 아직 없으면 polling loop(interval 200ms)로 마지막 id 이후 이벤트 재조회. `timeout` 도달 시 future fail.

이 방식은 pump 스레드를 쓰지 않아도 **Phase 1 블로킹 시나리오**에 충분하다. 다수 SSE 동시 구독자를 다루는 공유 pump 는 Phase 3(SSE) 에서 도입. Phase 1 에서는 **subscription 당 단일 ScheduledExecutorService 태스크** 로 구현.

> **스코프 변경 사유**: spec 4.5 의 pump thread 패턴은 수백 동시 SSE 구독자 관리용 최적화. 본 Phase 의 블로킹 모드는 요청당 1개 구독자 → 구독 수명 수초 이내 → 단순 ScheduledExecutor 폴링이 더 단순하고 안전. Phase 3(SSE) 에서 구독 수가 증가하면 pump 로 교체한다.
>
> 파일명은 `EventBusPumpThread.kt` 대신 `EventBusPoller.kt` 로 생성한다.

- [ ] **Step 1: `EventBusPoller.kt` 작성**

```kotlin
package com.bara.api.adapter.out.redis

import com.bara.api.application.port.out.Subscription
import com.bara.api.domain.model.TaskEvent
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.stream.ReadOffset
import org.springframework.data.redis.connection.stream.StreamOffset
import org.springframework.data.redis.connection.stream.StreamReadOptions
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Redis Stream 단일 구독자 polling 구현.
 *
 * 동작:
 *   1. 생성 직후 fromStreamId 로 backfill (XREAD COUNT 1000 STREAMS key fromId)
 *   2. 리스너에 각 이벤트 전달
 *   3. lastStreamId 갱신
 *   4. 200ms 간격으로 다음 엔트리 polling
 *   5. close() 호출 시 스케줄 cancel
 */
class EventBusPoller(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val streamKey: String,
    fromStreamId: String,
    private val listener: (TaskEvent) -> Unit,
    private val executor: ScheduledExecutorService,
    private val pollIntervalMs: Long = 200,
) : Subscription {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val lastId = AtomicReference(fromStreamId)
    private val scheduled: ScheduledFuture<*>
    @Volatile private var closed = false

    init {
        scheduled = executor.scheduleWithFixedDelay(
            { pollOnce() },
            0, pollIntervalMs, TimeUnit.MILLISECONDS,
        )
    }

    private fun pollOnce() {
        if (closed) return
        try {
            val offset = StreamOffset.create(streamKey, ReadOffset.from(lastId.get()))
            val opts = StreamReadOptions.empty().count(1000)
            val records = redisTemplate.opsForStream<Any, Any>()
                .read(opts, offset) ?: return
            for (record in records) {
                val json = record.value["event"]?.toString() ?: continue
                val event = TaskEventJson.deserialize(objectMapper, json)
                try {
                    listener(event)
                } catch (e: Throwable) {
                    logger.error("Subscriber listener threw for stream={} id={}", streamKey, record.id, e)
                }
                lastId.set(record.id.toString())
            }
        } catch (e: Throwable) {
            logger.warn("Poll error stream={}: {}", streamKey, e.message)
        }
    }

    override fun close() {
        closed = true
        scheduled.cancel(false)
    }

    companion object {
        fun newExecutor(): ScheduledExecutorService =
            Executors.newScheduledThreadPool(
                Runtime.getRuntime().availableProcessors(),
                { r -> Thread(r, "event-bus-poller").apply { isDaemon = true } },
            )
    }
}
```

- [ ] **Step 2: `RedisStreamTaskEventBus.kt` subscribe/await 구현**

기존 `TODO` 두 개를 실제 구현으로 교체. 생성자에 `ScheduledExecutorService` 주입. 파일 전체 교체:

```kotlin
package com.bara.api.adapter.out.redis

import com.bara.api.application.port.out.Subscription
import com.bara.api.application.port.out.TaskEventBusPort
import com.bara.api.config.TaskProperties
import com.bara.api.domain.model.TaskEvent
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.stream.StreamRecords
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Component
class RedisStreamTaskEventBus(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val properties: TaskProperties,
    private val executor: ScheduledExecutorService = EventBusPoller.newExecutor(),
) : TaskEventBusPort {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun publish(taskId: String, event: TaskEvent): String {
        val key = streamKey(taskId)
        val json = TaskEventJson.serialize(objectMapper, event)
        val record = StreamRecords.newRecord()
            .`in`(key)
            .ofMap(mapOf<Any, Any>("event" to json))
        val recordId = redisTemplate.opsForStream<Any, Any>().add(record)
            ?: error("Redis XADD returned null recordId for key=$key")
        return recordId.toString()
    }

    override fun subscribe(
        taskId: String,
        fromStreamId: String,
        listener: (TaskEvent) -> Unit,
    ): Subscription = EventBusPoller(
        redisTemplate = redisTemplate,
        objectMapper = objectMapper,
        streamKey = streamKey(taskId),
        fromStreamId = fromStreamId,
        listener = listener,
        executor = executor,
    )

    override fun await(taskId: String, timeout: Duration): CompletableFuture<TaskEvent> {
        val future = CompletableFuture<TaskEvent>()
        val subscription = subscribe(taskId, "0") { event ->
            if (event.final && !future.isDone) {
                future.complete(event)
            }
        }
        val timeoutFuture = executor.schedule({
            if (!future.isDone) {
                future.completeExceptionally(TimeoutException("await timeout taskId=$taskId"))
            }
        }, timeout.toMillis(), TimeUnit.MILLISECONDS)

        future.whenComplete { _, _ ->
            subscription.close()
            timeoutFuture.cancel(false)
        }
        return future
    }

    override fun close(taskId: String) {
        val key = streamKey(taskId)
        val grace = Duration.ofSeconds(properties.streamGracePeriodSeconds)
        redisTemplate.expire(key, grace)
        logger.debug("Scheduled stream close key={} after={}s", key, grace.seconds)
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdown()
    }

    private fun streamKey(taskId: String): String = "stream:task:$taskId"
}
```

- [ ] **Step 3: `RedisStreamTaskEventBusTest` 에 await 테스트 추가**

기존 테스트 파일 하단에 추가:

```kotlin
    @Test
    fun `await는 backfill 된 final 이벤트로 즉시 complete 된다`() {
        val streamOpsMock = streamOps
        every { redisTemplate.opsForStream<Any, Any>() } returns streamOpsMock

        // publish 호출용 mock (버스 내부에서 미사용하지만 필요 시)
        every {
            streamOpsMock.read(any<org.springframework.data.redis.connection.stream.StreamReadOptions>(),
                any<org.springframework.data.redis.connection.stream.StreamOffset<String>>())
        } returns listOf(
            org.springframework.data.redis.connection.stream.StreamRecords.newRecord()
                .`in`("stream:task:t-1")
                .ofMap(mapOf<Any, Any>(
                    "event" to """{
                        "taskId":"t-1","contextId":"c-1","state":"completed",
                        "final":true,"timestamp":"2026-04-11T00:00:00Z"
                    }""".trimIndent().replace("\n", ""),
                ))
                .withId(org.springframework.data.redis.connection.stream.RecordId.of(1L, 0L))
        )

        val executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        try {
            val bus = RedisStreamTaskEventBus(redisTemplate, objectMapper, properties, executor)
            val future = bus.await("t-1", Duration.ofSeconds(2))
            val event = future.get(1, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(TaskState.COMPLETED, event.state)
            assertEquals(true, event.final)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `await는 timeout 시 TimeoutException 을 반환한다`() {
        every { redisTemplate.opsForStream<Any, Any>() } returns streamOps
        every {
            streamOps.read(any<org.springframework.data.redis.connection.stream.StreamReadOptions>(),
                any<org.springframework.data.redis.connection.stream.StreamOffset<String>>())
        } returns emptyList()

        val executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()
        try {
            val bus = RedisStreamTaskEventBus(redisTemplate, objectMapper, properties, executor)
            val future = bus.await("t-1", Duration.ofMillis(300))
            val ex = kotlin.runCatching { future.get(1, java.util.concurrent.TimeUnit.SECONDS) }.exceptionOrNull()
            assertEquals(java.util.concurrent.TimeoutException::class, ex?.cause?.let { it::class })
        } finally {
            executor.shutdownNow()
        }
    }
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :apps:api:test --tests "com.bara.api.adapter.out.redis.RedisStreamTaskEventBusTest"`
Expected: PASS (4 tests)

- [ ] **Step 5: 커밋**

```bash
git add apps/api/src/main/kotlin/com/bara/api/adapter/out/redis/EventBusPoller.kt \
        apps/api/src/main/kotlin/com/bara/api/adapter/out/redis/RedisStreamTaskEventBus.kt \
        apps/api/src/test/kotlin/com/bara/api/adapter/out/redis/RedisStreamTaskEventBusTest.kt
git commit -m "feat(api): add Redis Stream subscribe/await via polling subscription"
```

---

### Task 9: Kafka consumer 설정 — `KafkaConsumerConfig`

**Files:**

- Create: `apps/api/src/main/kotlin/com/bara/api/config/KafkaConsumerConfig.kt`

현재 `application.yml` 의 `spring.kafka.consumer.group-id: api-service` 는 HTTP heartbeat 이후 사용처가 없지만, 새로 `api-service-results` 로 바꾸면 기존 Heartbeat consumer 가 있을 경우 영향. 영향 확인 필요.

> **사전 확인**: `apps/api/src/main/kotlin/com/bara/api/adapter/in/kafka/` 에 기존 consumer 가 있는지 `find` 로 확인. 없으면 group-id 자유롭게 변경. 있으면 별도 factory 로 분리.

본 Task 에서는 ResultConsumer 전용 `ConcurrentKafkaListenerContainerFactory` 를 명시적으로 정의해, spring.kafka.consumer 섹션과 격리한다.

- [ ] **Step 1: `KafkaConsumerConfig.kt` 작성**

```kotlin
package com.bara.api.config

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.listener.ContainerProperties

@Configuration
@EnableKafka
class KafkaConsumerConfig(
    @Value("\${spring.kafka.bootstrap-servers}")
    private val bootstrapServers: String,
) {

    @Bean(name = ["resultConsumerFactory"])
    fun resultConsumerFactory(): ConsumerFactory<String, String> {
        val props = mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
            ConsumerConfig.GROUP_ID_CONFIG to "api-service-results",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "latest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
        )
        return DefaultKafkaConsumerFactory(props)
    }

    @Bean(name = ["resultKafkaListenerContainerFactory"])
    fun resultKafkaListenerContainerFactory(
        resultConsumerFactory: ConsumerFactory<String, String>,
    ): ConcurrentKafkaListenerContainerFactory<String, String> {
        val factory = ConcurrentKafkaListenerContainerFactory<String, String>()
        factory.consumerFactory = resultConsumerFactory
        factory.containerProperties.ackMode = ContainerProperties.AckMode.MANUAL
        return factory
    }
}
```

- [ ] **Step 2: 컴파일 확인**

Run: `./gradlew :apps:api:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 커밋**

```bash
git add apps/api/src/main/kotlin/com/bara/api/config/KafkaConsumerConfig.kt
git commit -m "feat(api): add shared group KafkaConsumerConfig for results.api"
```

---

### Task 10: Kafka result consumer — `ResultConsumerAdapter`

**Files:**

- Create: `apps/api/src/main/kotlin/com/bara/api/adapter/in/kafka/ResultConsumerAdapter.kt`
- Create: `apps/api/src/test/kotlin/com/bara/api/adapter/in/kafka/ResultConsumerAdapterTest.kt`

**A2A TaskResult wire 포맷** (default agent 가 발행, 기존 구현 참고): JSON 으로 `task_id`, `context_id`, `state`(lowercase), `status_message`, `artifacts`, `error_code`, `error_message`, `final` 키를 포함. 본 Task 에서 정확 키는 default agent 구현을 기준으로 한다.

> **확인 필요**: default agent 의 `TaskResult` JSON 스키마를 읽기. 본 Plan 에서는 spec §2.4 와 동일한 포맷(`taskId`, `contextId`, `state`, `statusMessage`, `artifact`, `errorCode`, `errorMessage`, `final`, `timestamp`)을 가정하고, 배포 시 adapter 로 변환 로직 보강.

- [ ] **Step 1: `ResultConsumerAdapterTest.kt` failing 테스트 작성**

```kotlin
package com.bara.api.adapter.`in`.kafka

import com.bara.api.application.port.out.TaskEventBusPort
import com.bara.api.application.port.out.TaskRepositoryPort
import com.bara.api.config.TaskProperties
import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.A2APart
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskState
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.kafka.support.Acknowledgment
import java.time.Instant

class ResultConsumerAdapterTest {

    private val repository = mockk<TaskRepositoryPort>()
    private val eventBus = mockk<TaskEventBusPort>()
    private val properties = TaskProperties()
    private val mapper = ObjectMapper().registerKotlinModule()
    private val ack = mockk<Acknowledgment>(relaxed = true)

    private val adapter = ResultConsumerAdapter(repository, eventBus, mapper, properties)

    private val now = Instant.parse("2026-04-11T00:00:00Z")

    private val storedTask = Task(
        id = "t-1",
        agentId = "a-1",
        agentName = "my-agent",
        userId = "u-1",
        contextId = "c-1",
        state = TaskState.SUBMITTED,
        inputMessage = A2AMessage("m-1", "user", listOf(A2APart("text", "hi"))),
        requestId = "r-1",
        createdAt = now,
        updatedAt = now,
        expiredAt = now.plusSeconds(7 * 24 * 3600),
    )

    @Test
    fun `completed 결과 수신 시 Mongo update 후 EventBus publish 후 ack commit`() {
        val payload = """
            {
              "taskId":"t-1","contextId":"c-1","state":"completed",
              "statusMessage":{"messageId":"m-2","role":"agent","parts":[{"kind":"text","text":"done"}]},
              "artifact":null,"errorCode":null,"errorMessage":null,"final":true,
              "timestamp":"2026-04-11T00:00:01Z"
            }
        """.trimIndent()

        every { repository.findById("t-1") } returns storedTask
        every {
            repository.updateState(
                id = "t-1",
                state = TaskState.COMPLETED,
                statusMessage = any(),
                artifacts = emptyList(),
                errorCode = null,
                errorMessage = null,
                updatedAt = any(),
                completedAt = any(),
                expiredAt = any(),
            )
        } returns true
        every { eventBus.publish(eq("t-1"), any()) } returns "1-0"
        justRun { eventBus.close("t-1") }

        adapter.onMessage(payload, ack)

        verify {
            repository.updateState(
                id = "t-1",
                state = TaskState.COMPLETED,
                statusMessage = any(),
                artifacts = emptyList(),
                errorCode = null,
                errorMessage = null,
                updatedAt = any(),
                completedAt = any(),
                expiredAt = any(),
            )
            eventBus.publish("t-1", any())
            eventBus.close("t-1")
            ack.acknowledge()
        }
    }

    @Test
    fun `taskId가 Mongo에 없으면 skip 후 ack commit`() {
        val payload = """
            {"taskId":"missing","contextId":"c","state":"completed","final":true,"timestamp":"2026-04-11T00:00:00Z"}
        """.trimIndent()
        every { repository.findById("missing") } returns null

        adapter.onMessage(payload, ack)

        verify(exactly = 0) { eventBus.publish(any(), any()) }
        verify { ack.acknowledge() }
    }

    @Test
    fun `unsupported state input-required는 failed로 변환 기록`() {
        val payload = """
            {"taskId":"t-1","contextId":"c-1","state":"input-required","final":false,"timestamp":"2026-04-11T00:00:00Z"}
        """.trimIndent()
        every { repository.findById("t-1") } returns storedTask
        val stateSlot = slot<TaskState>()
        every {
            repository.updateState(
                id = "t-1", state = capture(stateSlot),
                statusMessage = any(), artifacts = any(),
                errorCode = "unsupported-state", errorMessage = any(),
                updatedAt = any(), completedAt = any(), expiredAt = any(),
            )
        } returns true
        every { eventBus.publish(eq("t-1"), any()) } returns "1-0"
        justRun { eventBus.close("t-1") }

        adapter.onMessage(payload, ack)

        assert(stateSlot.captured == TaskState.FAILED)
        verify { ack.acknowledge() }
    }
}
```

- [ ] **Step 2: 테스트 실행하여 실패 확인**

Run: `./gradlew :apps:api:test --tests "com.bara.api.adapter.in.kafka.ResultConsumerAdapterTest"`
Expected: FAIL — `ResultConsumerAdapter` 없음

- [ ] **Step 3: `ResultConsumerAdapter.kt` 작성**

```kotlin
package com.bara.api.adapter.`in`.kafka

import com.bara.api.application.port.out.TaskEventBusPort
import com.bara.api.application.port.out.TaskRepositoryPort
import com.bara.api.config.TaskProperties
import com.bara.api.domain.model.A2AArtifact
import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.TaskEvent
import com.bara.api.domain.model.TaskState
import com.bara.common.logging.WideEvent
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

@Component
class ResultConsumerAdapter(
    private val repository: TaskRepositoryPort,
    private val eventBus: TaskEventBusPort,
    private val objectMapper: ObjectMapper,
    private val properties: TaskProperties,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = [RESULT_TOPIC],
        containerFactory = "resultKafkaListenerContainerFactory",
    )
    fun onMessage(payload: String, ack: Acknowledgment) {
        try {
            val wire = parsePayload(payload) ?: run {
                logger.warn("Failed to parse result payload — skip")
                ack.acknowledge()
                return
            }

            val taskId = wire.taskId
            val existing = repository.findById(taskId)
            if (existing == null) {
                logger.warn("Task not found for result taskId={} — skip", taskId)
                WideEvent.put("task_id", taskId)
                WideEvent.put("outcome", "result_skipped_unknown")
                ack.acknowledge()
                return
            }

            val (state, errorCode, errorMessage) = translateState(wire)
            val now = Instant.now()
            val completedAt = if (state.isTerminal) now else null
            val expiredAt = if (state.isTerminal) {
                now.plus(Duration.ofDays(properties.mongoTtlDays))
            } else null

            val artifacts = wire.artifact?.let { listOf(it) } ?: emptyList()

            repository.updateState(
                id = taskId,
                state = state,
                statusMessage = wire.statusMessage,
                artifacts = artifacts,
                errorCode = errorCode,
                errorMessage = errorMessage,
                updatedAt = now,
                completedAt = completedAt,
                expiredAt = expiredAt,
            )

            val event = TaskEvent(
                taskId = taskId,
                contextId = wire.contextId,
                state = state,
                statusMessage = wire.statusMessage,
                artifact = wire.artifact,
                errorCode = errorCode,
                errorMessage = errorMessage,
                final = state.isTerminal,
                timestamp = now,
            )
            eventBus.publish(taskId, event)
            if (state.isTerminal) {
                eventBus.close(taskId)
            }

            WideEvent.put("task_id", taskId)
            WideEvent.put("state", state.name.lowercase())
            WideEvent.put("outcome", if (state.isTerminal) "result_processed" else "result_processed_intermediate")
            WideEvent.message("태스크 결과 처리 완료")
            ack.acknowledge()
        } catch (e: Throwable) {
            logger.error("ResultConsumer error — message will be re-delivered", e)
            throw e
        }
    }

    private fun parsePayload(payload: String): WireResult? =
        runCatching { objectMapper.readValue(payload, WireResult::class.java) }
            .getOrNull()

    private fun translateState(wire: WireResult): Triple<TaskState, String?, String?> {
        val raw = wire.state.lowercase()
        return when (raw) {
            "submitted" -> Triple(TaskState.SUBMITTED, null, null)
            "working" -> Triple(TaskState.WORKING, null, null)
            "completed" -> Triple(TaskState.COMPLETED, null, null)
            "failed" -> Triple(TaskState.FAILED, wire.errorCode, wire.errorMessage)
            "canceled" -> Triple(TaskState.CANCELED, null, null)
            "rejected" -> Triple(TaskState.REJECTED, wire.errorCode ?: "rejected", wire.errorMessage)
            "input-required", "auth-required" ->
                Triple(TaskState.FAILED, "unsupported-state", "Agent returned unsupported state=$raw")
            else ->
                Triple(TaskState.FAILED, "unsupported-state", "Unknown state=$raw")
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class WireResult(
        val taskId: String,
        val contextId: String,
        val state: String,
        val statusMessage: A2AMessage? = null,
        val artifact: A2AArtifact? = null,
        val errorCode: String? = null,
        val errorMessage: String? = null,
        val final: Boolean = false,
        val timestamp: String? = null,
    )

    companion object {
        const val RESULT_TOPIC = "results.api"
    }
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `./gradlew :apps:api:test --tests "com.bara.api.adapter.in.kafka.ResultConsumerAdapterTest"`
Expected: PASS (3 tests)

- [ ] **Step 5: 커밋**

```bash
git add apps/api/src/main/kotlin/com/bara/api/adapter/in/kafka/ResultConsumerAdapter.kt \
        apps/api/src/test/kotlin/com/bara/api/adapter/in/kafka/ResultConsumerAdapterTest.kt
git commit -m "feat(api): add Kafka ResultConsumerAdapter for results.api"
```

---

### Task 11: A2A 응답 DTO + Mapper

**Files:**

- Create: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/a2a/A2ATaskDto.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/a2a/A2ATaskMapper.kt`
- Create: `apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/a2a/A2ATaskMapperTest.kt`

단일 파일 `A2ATaskDto.kt` 에 관련 DTO를 모두 포함 (A2ATaskDto, A2ATaskStatusDto, A2AMessageDto, A2AArtifactDto). Phase 1 에서는 JsonRpcResponse 랩핑 없이 DTO 자체를 응답한다.

- [ ] **Step 1: `A2ATaskMapperTest.kt` failing 테스트 작성**

```kotlin
package com.bara.api.adapter.`in`.rest.a2a

import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.A2APart
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskEvent
import com.bara.api.domain.model.TaskState
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

class A2ATaskMapperTest {

    private val now = Instant.parse("2026-04-11T00:00:00Z")

    private val baseTask = Task(
        id = "t-1",
        agentId = "a-1",
        agentName = "my-agent",
        userId = "u-1",
        contextId = "c-1",
        state = TaskState.COMPLETED,
        inputMessage = A2AMessage("m-1", "user", listOf(A2APart("text", "hi"))),
        statusMessage = A2AMessage("m-2", "agent", listOf(A2APart("text", "done"))),
        requestId = "r-1",
        createdAt = now,
        updatedAt = now,
        completedAt = now,
        expiredAt = now.plusSeconds(7 * 24 * 3600),
    )

    @Test
    fun `Task를 A2ATaskDto로 변환한다`() {
        val dto = A2ATaskMapper.toDto(baseTask)
        assertEquals("t-1", dto.id)
        assertEquals("c-1", dto.contextId)
        assertEquals("completed", dto.status.state)
        assertEquals("done", dto.status.message?.parts?.firstOrNull()?.text)
        assertEquals("task", dto.kind)
    }

    @Test
    fun `TaskEvent와 baseTask로부터 A2ATaskDto를 합성한다`() {
        val event = TaskEvent(
            taskId = "t-1",
            contextId = "c-1",
            state = TaskState.FAILED,
            statusMessage = A2AMessage("m-3", "agent", listOf(A2APart("text", "err"))),
            errorCode = "agent-failure",
            errorMessage = "boom",
            final = true,
            timestamp = now,
        )
        val dto = A2ATaskMapper.fromEvent(baseTask, event)
        assertEquals("failed", dto.status.state)
        assertEquals("err", dto.status.message?.parts?.firstOrNull()?.text)
    }

    @Test
    fun `TaskState enum name을 A2A wire 문자열로 변환한다`() {
        assertEquals("submitted", A2ATaskMapper.stateToWire(TaskState.SUBMITTED))
        assertEquals("completed", A2ATaskMapper.stateToWire(TaskState.COMPLETED))
        assertEquals("canceled", A2ATaskMapper.stateToWire(TaskState.CANCELED))
    }
}
```

- [ ] **Step 2: 테스트 실행하여 실패 확인**

Run: `./gradlew :apps:api:test --tests "com.bara.api.adapter.in.rest.a2a.A2ATaskMapperTest"`
Expected: FAIL — DTO / Mapper 없음

- [ ] **Step 3: `A2ATaskDto.kt` 작성**

```kotlin
package com.bara.api.adapter.`in`.rest.a2a

data class A2ATaskDto(
    val id: String,
    val contextId: String,
    val status: A2ATaskStatusDto,
    val artifacts: List<A2AArtifactDto> = emptyList(),
    val kind: String = "task",
    val metadata: Map<String, Any?> = emptyMap(),
)

data class A2ATaskStatusDto(
    val state: String,
    val message: A2AMessageDto? = null,
    val timestamp: String? = null,
)

data class A2AMessageDto(
    val messageId: String,
    val role: String,
    val parts: List<A2APartDto>,
)

data class A2APartDto(
    val kind: String,
    val text: String,
)

data class A2AArtifactDto(
    val artifactId: String,
    val name: String? = null,
    val parts: List<A2APartDto> = emptyList(),
)
```

- [ ] **Step 4: `A2ATaskMapper.kt` 작성**

```kotlin
package com.bara.api.adapter.`in`.rest.a2a

import com.bara.api.domain.model.A2AArtifact
import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskEvent
import com.bara.api.domain.model.TaskState

object A2ATaskMapper {

    fun toDto(task: Task): A2ATaskDto = A2ATaskDto(
        id = task.id,
        contextId = task.contextId,
        status = A2ATaskStatusDto(
            state = stateToWire(task.state),
            message = task.statusMessage?.let(::toMessageDto),
            timestamp = task.updatedAt.toString(),
        ),
        artifacts = task.artifacts.map(::toArtifactDto),
    )

    fun fromEvent(task: Task, event: TaskEvent): A2ATaskDto = A2ATaskDto(
        id = event.taskId,
        contextId = event.contextId,
        status = A2ATaskStatusDto(
            state = stateToWire(event.state),
            message = event.statusMessage?.let(::toMessageDto),
            timestamp = event.timestamp.toString(),
        ),
        artifacts = listOfNotNull(event.artifact?.let(::toArtifactDto)).ifEmpty {
            task.artifacts.map(::toArtifactDto)
        },
    )

    fun stateToWire(state: TaskState): String = when (state) {
        TaskState.SUBMITTED -> "submitted"
        TaskState.WORKING -> "working"
        TaskState.COMPLETED -> "completed"
        TaskState.FAILED -> "failed"
        TaskState.CANCELED -> "canceled"
        TaskState.REJECTED -> "rejected"
    }

    private fun toMessageDto(m: A2AMessage): A2AMessageDto = A2AMessageDto(
        messageId = m.messageId,
        role = m.role,
        parts = m.parts.map { A2APartDto(kind = it.kind, text = it.text) },
    )

    private fun toArtifactDto(a: A2AArtifact): A2AArtifactDto = A2AArtifactDto(
        artifactId = a.artifactId,
        name = a.name,
        parts = a.parts.map { A2APartDto(kind = it.kind, text = it.text) },
    )
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :apps:api:test --tests "com.bara.api.adapter.in.rest.a2a.A2ATaskMapperTest"`
Expected: PASS (3 tests)

- [ ] **Step 6: 커밋**

```bash
git add apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/a2a/
git add apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/a2a/
git commit -m "feat(api): add A2A Task DTOs and mapper"
```

---

### Task 12: `SendMessageService` 블로킹 rewrite

**Files:**

- Modify: `apps/api/src/main/kotlin/com/bara/api/application/port/in/command/SendMessageUseCase.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/application/service/command/SendMessageService.kt`
- Modify: `apps/api/src/test/kotlin/com/bara/api/application/service/command/SendMessageServiceTest.kt`

서비스는 `CompletableFuture<A2ATaskDto>` 를 반환. 내부 흐름은 spec 시나리오 A 의 ①~⑩ 를 그대로 구현한다.

- [ ] **Step 1: `SendMessageUseCase.kt` 시그니처 변경**

```kotlin
package com.bara.api.application.port.`in`.command

import com.bara.api.adapter.`in`.rest.a2a.A2ATaskDto
import java.util.concurrent.CompletableFuture

interface SendMessageUseCase {
    fun sendBlocking(
        userId: String,
        agentName: String,
        request: SendMessageRequest,
    ): CompletableFuture<A2ATaskDto>

    data class SendMessageRequest(
        val messageId: String,
        val text: String,
        val contextId: String?,
    )
}
```

> 주: port 레이어가 adapter 패키지를 참조하는 건 hexagonal 원칙 위반이지만, Phase 1 은 단일 모드(`blocking` 반환)라서 간결성을 위해 허용. Phase 2 에서 `A2ATaskDto` 를 도메인 응답 타입으로 끌어올리거나 별도 result wrapper 를 만들 예정.

- [ ] **Step 2: `SendMessageServiceTest.kt` failing 테스트로 전면 교체**

```kotlin
package com.bara.api.application.service.command

import com.bara.api.application.port.`in`.command.SendMessageUseCase
import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.TaskEventBusPort
import com.bara.api.application.port.out.TaskMessagePayload
import com.bara.api.application.port.out.TaskPublisherPort
import com.bara.api.application.port.out.TaskRepositoryPort
import com.bara.api.config.TaskProperties
import com.bara.api.domain.exception.AgentTimeoutException
import com.bara.api.domain.exception.AgentUnavailableException
import com.bara.api.domain.exception.KafkaPublishException
import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.A2APart
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskEvent
import com.bara.api.domain.model.TaskState
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SendMessageServiceTest {

    private val agentRegistryPort = mockk<AgentRegistryPort>()
    private val taskPublisherPort = mockk<TaskPublisherPort>()
    private val taskRepositoryPort = mockk<TaskRepositoryPort>()
    private val taskEventBusPort = mockk<TaskEventBusPort>()
    private val properties = TaskProperties(blockTimeoutSeconds = 2)

    private val service = SendMessageService(
        agentRegistryPort = agentRegistryPort,
        taskPublisherPort = taskPublisherPort,
        taskRepositoryPort = taskRepositoryPort,
        taskEventBusPort = taskEventBusPort,
        properties = properties,
    )

    private val request = SendMessageUseCase.SendMessageRequest(
        messageId = "m-1", text = "안녕", contextId = "c-1",
    )

    private fun storedTask(state: TaskState = TaskState.SUBMITTED): Task {
        val now = Instant.now()
        return Task(
            id = "t-1",
            agentId = "agent-001",
            agentName = "my-agent",
            userId = "user-1",
            contextId = "c-1",
            state = state,
            inputMessage = A2AMessage("m-1", "user", listOf(A2APart("text", "안녕"))),
            requestId = "r-1",
            createdAt = now,
            updatedAt = now,
            expiredAt = now.plusSeconds(7 * 24 * 3600),
        )
    }

    @Test
    fun `정상 블로킹 플로우 — completed 이벤트를 받으면 A2ATaskDto를 반환한다`() {
        every { agentRegistryPort.getAgentId("my-agent") } returns "agent-001"
        val taskSlot = slot<Task>()
        every { taskRepositoryPort.save(capture(taskSlot)) } answers { taskSlot.captured }
        every { taskRepositoryPort.findById(any()) } answers { taskSlot.captured }
        every { taskEventBusPort.publish(any(), any()) } returns "1-0"
        justRun { taskPublisherPort.publish("agent-001", any<TaskMessagePayload>()) }

        val completedEvent = TaskEvent(
            taskId = "_will_be_overridden",
            contextId = "c-1",
            state = TaskState.COMPLETED,
            statusMessage = A2AMessage("m-2", "agent", listOf(A2APart("text", "done"))),
            final = true,
            timestamp = Instant.now(),
        )
        every { taskEventBusPort.await(any(), any()) } answers {
            val tid = firstArg<String>()
            CompletableFuture.completedFuture(completedEvent.copy(taskId = tid))
        }

        val future = service.sendBlocking("user-1", "my-agent", request)
        val dto = future.get(1, TimeUnit.SECONDS)

        assertEquals("completed", dto.status.state)
        verify { taskRepositoryPort.save(any<Task>()) }
        verify { taskEventBusPort.publish(any(), any()) }      // submitted
        verify { taskPublisherPort.publish("agent-001", any<TaskMessagePayload>()) }
        verify { taskEventBusPort.await(any(), any()) }
    }

    @Test
    fun `Agent가 레지스트리에 없으면 AgentUnavailable 예외`() {
        every { agentRegistryPort.getAgentId("dead") } returns null

        val ex = runCatching {
            service.sendBlocking("user-1", "dead", request).get()
        }.exceptionOrNull()
        assertTrue(ex?.cause is AgentUnavailableException || ex is AgentUnavailableException)
    }

    @Test
    fun `Kafka publish 실패 시 Task를 failed 로 전환하고 KafkaPublishException 전파`() {
        every { agentRegistryPort.getAgentId("my-agent") } returns "agent-001"
        val taskSlot = slot<Task>()
        every { taskRepositoryPort.save(capture(taskSlot)) } answers { taskSlot.captured }
        every { taskEventBusPort.publish(any(), any()) } returns "1-0"
        every { taskPublisherPort.publish("agent-001", any<TaskMessagePayload>()) } throws
            KafkaPublishException("broker down")
        every {
            taskRepositoryPort.updateState(
                id = any(), state = TaskState.FAILED,
                statusMessage = any(), artifacts = any(),
                errorCode = any(), errorMessage = any(),
                updatedAt = any(), completedAt = any(), expiredAt = any(),
            )
        } returns true

        val future = service.sendBlocking("user-1", "my-agent", request)
        val ex = runCatching { future.get(1, TimeUnit.SECONDS) }.exceptionOrNull()
        assertTrue((ex as? ExecutionException)?.cause is KafkaPublishException)
        verify {
            taskRepositoryPort.updateState(
                id = any(), state = TaskState.FAILED,
                statusMessage = any(), artifacts = any(),
                errorCode = "kafka-publish-failed", errorMessage = any(),
                updatedAt = any(), completedAt = any(), expiredAt = any(),
            )
        }
    }

    @Test
    fun `await 타임아웃이면 AgentTimeoutException 전파`() {
        every { agentRegistryPort.getAgentId("my-agent") } returns "agent-001"
        val taskSlot = slot<Task>()
        every { taskRepositoryPort.save(capture(taskSlot)) } answers { taskSlot.captured }
        every { taskEventBusPort.publish(any(), any()) } returns "1-0"
        justRun { taskPublisherPort.publish("agent-001", any<TaskMessagePayload>()) }
        every { taskEventBusPort.await(any(), any()) } answers {
            val f = CompletableFuture<TaskEvent>()
            f.completeExceptionally(TimeoutException("await"))
            f
        }

        val future = service.sendBlocking("user-1", "my-agent", request)
        val ex = runCatching { future.get(1, TimeUnit.SECONDS) }.exceptionOrNull()
        assertTrue((ex as? ExecutionException)?.cause is AgentTimeoutException)
    }
}
```

- [ ] **Step 3: 테스트 실행하여 실패 확인**

Run: `./gradlew :apps:api:test --tests "com.bara.api.application.service.command.SendMessageServiceTest"`
Expected: FAIL — `sendBlocking` / 새 시그니처 없음

- [ ] **Step 4: `SendMessageService.kt` 전체 교체**

```kotlin
package com.bara.api.application.service.command

import com.bara.api.adapter.`in`.rest.a2a.A2ATaskDto
import com.bara.api.adapter.`in`.rest.a2a.A2ATaskMapper
import com.bara.api.application.port.`in`.command.SendMessageUseCase
import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.TaskEventBusPort
import com.bara.api.application.port.out.TaskMessagePayload
import com.bara.api.application.port.out.TaskPublisherPort
import com.bara.api.application.port.out.TaskRepositoryPort
import com.bara.api.config.TaskProperties
import com.bara.api.domain.exception.AgentTimeoutException
import com.bara.api.domain.exception.AgentUnavailableException
import com.bara.api.domain.exception.KafkaPublishException
import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.A2APart
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskEvent
import com.bara.api.domain.model.TaskState
import com.bara.common.logging.WideEvent
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeoutException

@Service
class SendMessageService(
    private val agentRegistryPort: AgentRegistryPort,
    private val taskPublisherPort: TaskPublisherPort,
    private val taskRepositoryPort: TaskRepositoryPort,
    private val taskEventBusPort: TaskEventBusPort,
    private val properties: TaskProperties,
) : SendMessageUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun sendBlocking(
        userId: String,
        agentName: String,
        request: SendMessageUseCase.SendMessageRequest,
    ): CompletableFuture<A2ATaskDto> {
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

        // ① Mongo insert submitted
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

        // ② EventBus publish submitted — 블로킹 await 등록 전이어도 backfill로 수신되지만 일관성 위해 먼저.
        taskEventBusPort.publish(taskId, TaskEvent.of(task))

        // ③ 블로킹 await 등록
        val future = taskEventBusPort.await(
            taskId = taskId,
            timeout = Duration.ofSeconds(properties.blockTimeoutSeconds),
        )

        // ④ Kafka publish (ack 대기). 실패 시 즉시 failed 전환.
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
            future.cancel(true)
            WideEvent.put("task_id", taskId)
            WideEvent.put("agent_name", agentName)
            WideEvent.put("outcome", "kafka_publish_failed")
            WideEvent.message("Kafka publish 실패")
            throw e
        }

        logBlockingStart(taskId, agentName, agentId, userId)

        return future.handle { event, throwable ->
            when {
                throwable != null -> handleAwaitFailure(task, throwable)
                event != null -> {
                    WideEvent.put("task_id", taskId)
                    WideEvent.put("outcome", "task_${event.state.name.lowercase()}")
                    WideEvent.message("태스크 블로킹 완료")
                    A2ATaskMapper.fromEvent(task, event)
                }
                else -> error("unreachable")
            }
        }
    }

    private fun handleAwaitFailure(task: Task, throwable: Throwable): Nothing {
        val cause = (throwable as? java.util.concurrent.CompletionException)?.cause ?: throwable
        if (cause is TimeoutException) {
            WideEvent.put("task_id", task.id)
            WideEvent.put("outcome", "agent_timeout")
            WideEvent.message("Agent 응답 타임아웃")
            throw AgentTimeoutException("Agent did not respond within ${properties.blockTimeoutSeconds}s")
        }
        WideEvent.put("task_id", task.id)
        WideEvent.put("outcome", "task_failed")
        WideEvent.message("태스크 대기 중 오류: ${cause.message}")
        throw cause
    }

    private fun markFailed(task: Task, errorCode: String, errorMessage: String) {
        val now = Instant.now()
        val expiredAt = now.plus(Duration.ofDays(properties.mongoTtlDays))
        taskRepositoryPort.updateState(
            id = task.id,
            state = TaskState.FAILED,
            statusMessage = null,
            artifacts = emptyList(),
            errorCode = errorCode,
            errorMessage = errorMessage,
            updatedAt = now,
            completedAt = now,
            expiredAt = expiredAt,
        )
        taskEventBusPort.publish(
            task.id,
            TaskEvent(
                taskId = task.id,
                contextId = task.contextId,
                state = TaskState.FAILED,
                statusMessage = null,
                errorCode = errorCode,
                errorMessage = errorMessage,
                final = true,
                timestamp = now,
            ),
        )
    }

    private fun logBlockingStart(taskId: String, agentName: String, agentId: String, userId: String) {
        WideEvent.put("task_id", taskId)
        WideEvent.put("agent_name", agentName)
        WideEvent.put("agent_id", agentId)
        WideEvent.put("user_id", userId)
        logger.debug("Blocking send started taskId={}", taskId)
    }
}
```

- [ ] **Step 5: `AgentController.sendMessage` 시그니처 최소 수정 (컴파일 유지)**

Task 13 에서 async dispatch 로 완성하기 전, 일단 `sendBlocking` 호출로 컴파일만 맞춘다. `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentController.kt` 의 `sendMessage` 핸들러를 아래로 교체:

```kotlin
    @PostMapping("/{agentName}/message:send")
    fun sendMessage(
        @RequestHeader("X-User-Id") userId: String,
        @PathVariable agentName: String,
        @RequestBody request: SendMessageApiRequest,
    ): org.springframework.http.ResponseEntity<com.bara.api.adapter.`in`.rest.a2a.A2ATaskDto> {
        val text = request.message.parts.firstOrNull()?.text ?: ""
        val sendRequest = SendMessageUseCase.SendMessageRequest(
            messageId = request.message.messageId,
            text = text,
            contextId = request.contextId,
        )
        val dto = sendMessageUseCase.sendBlocking(userId, agentName, sendRequest).get()
        return org.springframework.http.ResponseEntity.ok(dto)
    }
```

> 주: 이 구현은 서블릿 스레드를 블로킹한다. Task 13 에서 `CompletableFuture<ResponseEntity<...>>` 반환으로 교체하여 async dispatch 로 전환한다.

- [ ] **Step 6: `AgentControllerTest` 의 기존 `message send` 테스트 두 개 삭제**

`AgentControllerTest.kt` 에서 아래 두 테스트 메서드를 제거 (Task 13 에서 재작성됨):

- `POST agents message send 성공 시 taskId 반환`
- `POST agents message send Agent 비활성 시 503`

`@MockkBean` 선언은 유지한다.

- [ ] **Step 7: 테스트 통과 확인**

Run: `./gradlew :apps:api:test --tests "com.bara.api.application.service.command.SendMessageServiceTest"`
Expected: PASS (4 tests)

Run: `./gradlew :apps:api:test --tests "com.bara.api.adapter.in.rest.AgentControllerTest"`
Expected: PASS (기존 테스트 중 message send 두 개 제외 나머지 녹색)

Run: `./gradlew :apps:api:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: 커밋**

```bash
git add apps/api/src/main/kotlin/com/bara/api/application/port/in/command/SendMessageUseCase.kt \
        apps/api/src/main/kotlin/com/bara/api/application/service/command/SendMessageService.kt \
        apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentController.kt \
        apps/api/src/test/kotlin/com/bara/api/application/service/command/SendMessageServiceTest.kt \
        apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/AgentControllerTest.kt
git commit -m "feat(api): rewrite SendMessageService for blocking A2A task flow"
```

---

### Task 13: Controller + 예외 핸들러

**Files:**

- Modify: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentController.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentDtos.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/ApiExceptionHandler.kt`
- Modify: `apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/AgentControllerTest.kt`

- [ ] **Step 1: `AgentDtos.kt` 에서 `SendMessageApiResponse` 제거, Request 는 유지**

기존 `data class SendMessageApiResponse(val taskId: String)` 선언만 삭제. 나머지 DTO (`SendMessageApiRequest`, `MessageRequest`, `PartRequest`) 는 그대로 둔다.

- [ ] **Step 2: `AgentController.sendMessage` 교체**

기존 `sendMessage` 핸들러를 아래로 교체:

```kotlin
    @PostMapping("/{agentName}/message:send")
    fun sendMessage(
        @RequestHeader("X-User-Id") userId: String,
        @PathVariable agentName: String,
        @RequestBody request: SendMessageApiRequest,
    ): java.util.concurrent.CompletableFuture<
        org.springframework.http.ResponseEntity<
            com.bara.api.adapter.`in`.rest.a2a.A2ATaskDto>> {
        val text = request.message.parts.firstOrNull()?.text ?: ""
        val sendRequest = SendMessageUseCase.SendMessageRequest(
            messageId = request.message.messageId,
            text = text,
            contextId = request.contextId,
        )
        return sendMessageUseCase.sendBlocking(userId, agentName, sendRequest)
            .thenApply { org.springframework.http.ResponseEntity.ok(it) }
    }
```

> Spring MVC 는 `CompletableFuture<ResponseEntity<T>>` 를 async dispatch 로 자동 처리한다. 대기 동안 Tomcat 스레드는 해제된다.

- [ ] **Step 3: `ApiExceptionHandler.kt` 에 `AgentTimeoutException`, `KafkaPublishException` 추가**

파일 하단에 두 핸들러 추가:

```kotlin
    @ExceptionHandler(com.bara.api.domain.exception.AgentTimeoutException::class)
    fun handleAgentTimeout(ex: com.bara.api.domain.exception.AgentTimeoutException): ResponseEntity<ErrorResponse> {
        WideEvent.put("error_type", "AgentTimeoutException")
        WideEvent.put("outcome", "agent_timeout")
        WideEvent.message("Agent 응답 타임아웃")
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
            .body(ErrorResponse("agent_timeout", ex.message ?: "Agent did not respond within timeout"))
    }

    @ExceptionHandler(com.bara.api.domain.exception.KafkaPublishException::class)
    fun handleKafkaPublish(ex: com.bara.api.domain.exception.KafkaPublishException): ResponseEntity<ErrorResponse> {
        WideEvent.put("error_type", "KafkaPublishException")
        WideEvent.put("outcome", "kafka_publish_failed")
        WideEvent.message("Kafka publish 실패")
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(ErrorResponse("kafka_publish_failed", ex.message ?: "Kafka publish failed"))
    }
```

- [ ] **Step 4: `AgentControllerTest.kt` message:send 케이스 업데이트**

기존 두 테스트 `POST agents message send 성공 시 taskId 반환` 과 `POST agents message send Agent 비활성 시 503` 을 교체하고 타임아웃/Kafka 실패 케이스 추가. `@MockkBean` 목록에 새 타입은 없음(기존 `SendMessageUseCase` 재활용).

테스트 파일에서 해당 두 `@Test` 메서드를 아래로 교체:

```kotlin
    @Test
    fun `POST agents message send 성공 시 200 + A2ATaskDto`() {
        val dto = com.bara.api.adapter.`in`.rest.a2a.A2ATaskDto(
            id = "t-1",
            contextId = "c-1",
            status = com.bara.api.adapter.`in`.rest.a2a.A2ATaskStatusDto(
                state = "completed",
                message = com.bara.api.adapter.`in`.rest.a2a.A2AMessageDto(
                    messageId = "m-2", role = "agent",
                    parts = listOf(com.bara.api.adapter.`in`.rest.a2a.A2APartDto("text", "done")),
                ),
                timestamp = "2026-04-11T00:00:00Z",
            ),
        )
        every {
            sendMessageUseCase.sendBlocking(eq("user-1"), eq("my-agent"), any())
        } returns java.util.concurrent.CompletableFuture.completedFuture(dto)

        val mvcResult = mockMvc.post("/agents/my-agent/message:send") {
            header("X-User-Id", "user-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"message":{"messageId":"msg-1","parts":[{"text":"hello"}]},"contextId":"ctx-1"}"""
        }.andExpect {
            request { asyncStarted() }
        }.andReturn()

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(mvcResult))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.id").value("t-1"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status.state").value("completed"))
    }

    @Test
    fun `POST agents message send Agent 비활성 시 503`() {
        every { sendMessageUseCase.sendBlocking(any(), eq("dead"), any()) } throws AgentUnavailableException()

        mockMvc.post("/agents/dead/message:send") {
            header("X-User-Id", "user-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"message":{"messageId":"msg-1","parts":[{"text":"hi"}]}}"""
        }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.error") { value("agent_unavailable") }
        }
    }

    @Test
    fun `POST agents message send Agent 타임아웃 시 504`() {
        val failed = java.util.concurrent.CompletableFuture<com.bara.api.adapter.`in`.rest.a2a.A2ATaskDto>()
        failed.completeExceptionally(com.bara.api.domain.exception.AgentTimeoutException())
        every { sendMessageUseCase.sendBlocking(any(), eq("slow"), any()) } returns failed

        val mvcResult = mockMvc.post("/agents/slow/message:send") {
            header("X-User-Id", "user-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"message":{"messageId":"msg-1","parts":[{"text":"hi"}]}}"""
        }.andExpect {
            request { asyncStarted() }
        }.andReturn()

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(mvcResult))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isGatewayTimeout)
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error").value("agent_timeout"))
    }

    @Test
    fun `POST agents message send Kafka 실패 시 502`() {
        every { sendMessageUseCase.sendBlocking(any(), eq("my-agent"), any()) } throws
            com.bara.api.domain.exception.KafkaPublishException("broker down")

        mockMvc.post("/agents/my-agent/message:send") {
            header("X-User-Id", "user-1")
            contentType = MediaType.APPLICATION_JSON
            content = """{"message":{"messageId":"msg-1","parts":[{"text":"hi"}]}}"""
        }.andExpect {
            status { isBadGateway() }
            jsonPath("$.error") { value("kafka_publish_failed") }
        }
    }
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `./gradlew :apps:api:test --tests "com.bara.api.adapter.in.rest.AgentControllerTest"`
Expected: PASS (기존 케이스 + 새 4개, 총 17개 내외)

- [ ] **Step 6: 커밋**

```bash
git add apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/ \
        apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/AgentControllerTest.kt
git commit -m "feat(api): wire blocking A2A task response in AgentController"
```

---

### Task 14: `application.yml` + logging flow doc + 전체 빌드

**Files:**

- Modify: `apps/api/src/main/resources/application.yml`
- Create: `docs/guides/logging/flows/api-task-processing.md`

- [ ] **Step 1: `application.yml` 갱신**

기존 `spring.kafka` 섹션을 아래로 교체:

```yaml
kafka:
  bootstrap-servers: kafka-0.kafka.data.svc.cluster.local:9092
  producer:
    acks: all
    delivery-timeout-ms: 5000
    retries: 3
    key-serializer: org.apache.kafka.common.serialization.StringSerializer
    value-serializer: org.apache.kafka.common.serialization.StringSerializer
  consumer:
    group-id: api-service-results
    enable-auto-commit: false
    auto-offset-reset: latest
    key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
    value-deserializer: org.apache.kafka.common.serialization.StringDeserializer
  listener:
    ack-mode: manual
```

파일 하단 `bara:` 섹션에 아래를 추가:

```yaml
api:
  task:
    block-timeout-seconds: 30
    kafka-publish-timeout-seconds: 5
    stream-grace-period-seconds: 60
    mongo-ttl-days: 7
```

- [ ] **Step 2: `docs/guides/logging/flows/api-task-processing.md` 작성**

```markdown
# API Task Processing 로깅 필드

Phase 1 시나리오: `POST /agents/{agentName}/message:send` 블로킹 동기 모드.

## POST /agents/{agentName}/message:send (블로킹)

| 필드       | 값                     | 설명                    |
| ---------- | ---------------------- | ----------------------- |
| task_id    | UUID                   | 생성된 Task ID          |
| agent_name | 문자열                 | 대상 Agent 이름         |
| agent_id   | UUID                   | 레지스트리 resolve 결과 |
| user_id    | UUID                   | 요청 User ID            |
| outcome    | `task_completed`       | 정상 완료               |
| outcome    | `task_failed`          | Agent가 failed 반환     |
| outcome    | `task_canceled`        | Agent가 canceled 반환   |
| outcome    | `task_rejected`        | Agent가 rejected 반환   |
| outcome    | `agent_unavailable`    | Registry 미등록         |
| outcome    | `agent_timeout`        | 30초 내 Agent 무응답    |
| outcome    | `kafka_publish_failed` | Kafka publish ack 실패  |

## Kafka ResultConsumer (results.api)

| 필드    | 값                                                               | 설명                    |
| ------- | ---------------------------------------------------------------- | ----------------------- |
| task_id | UUID                                                             | 처리 대상 Task          |
| state   | `submitted`/`working`/`completed`/`failed`/`canceled`/`rejected` | 결과 상태               |
| outcome | `result_processed`                                               | 터미널 상태 처리 완료   |
| outcome | `result_processed_intermediate`                                  | 비터미널 상태 처리 완료 |
| outcome | `result_skipped_unknown`                                         | taskId 가 Mongo 에 없음 |
```

- [ ] **Step 3: 전체 API 테스트 실행**

Run: `./gradlew :apps:api:test`
Expected: BUILD SUCCESSFUL — Phase 1 전체 테스트 green

- [ ] **Step 4: 전체 빌드**

Run: `./gradlew :apps:api:build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
git add apps/api/src/main/resources/application.yml \
        docs/guides/logging/flows/api-task-processing.md
git commit -m "feat(api): configure task processing Kafka consumer and properties"
```

---

## 완료 기준 (Definition of Done)

- [ ] `./gradlew :apps:api:test` green
- [ ] `./gradlew :apps:api:build` green
- [ ] `POST /agents/{agentName}/message:send` 가 `CompletableFuture<ResponseEntity<A2ATaskDto>>` 를 반환 (async dispatch)
- [ ] Kafka `results.api` 메시지 수신 시 Mongo update + EventBus publish + ack commit
- [ ] Redis Stream `stream:task:{taskId}` 로 `submitted`/`completed`/`failed` 이벤트 저장
- [ ] Task TTL 7 일 설정 (MongoDB `tasks` 컬렉션 `expiredAt` 인덱스)
- [ ] Kafka publish 실패 시 즉시 Task `failed` 전환 + 502 응답
- [ ] Await timeout (30초) 시 504 응답, Mongo 상태는 유지
- [ ] WideEvent 로깅 필드 모두 기록, `api-task-processing.md` 가이드 작성

## Phase 1 에서 의도적으로 **제외**한 항목

다음은 Phase 2 / Phase 3 에서 구현:

- `returnImmediately=true` 비동기 + `GET /agents/{name}/tasks/{taskId}` 폴링
- `POST /agents/{name}/message:stream` SSE
- `GET /agents/{name}/tasks/{taskId}:subscribe` SSE 재연결 + Last-Event-ID
- 완전한 JSON-RPC envelope (`JsonRpcRequest` / `JsonRpcResponse` / `JsonRpcError`)
- `A2AException` base class + `A2AExceptionHandler` (Phase 1 은 기존 `ApiExceptionHandler` 에 직접 추가)
- `TaskAccessDeniedException`, `TaskNotFoundException`, `StreamUnsupportedException`
- Redis Stream pump thread 패턴 (본 Phase 는 subscription 당 polling 으로 대체, SSE 다중 구독 시 pump 재도입)
- 멀티턴 `historyLength`, `pushNotificationConfig`, Kafka DLQ, Micrometer metric
- Task state `input-required`, `auth-required` 수용 (수신 시 `failed` 변환 처리만)

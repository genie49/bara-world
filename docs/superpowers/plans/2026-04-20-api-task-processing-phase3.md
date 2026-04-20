# API Task Processing Phase 3 (SSE 스트리밍 + 재연결) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /agents/{name}/message:stream` (SSE 스트리밍)과 `GET /agents/{name}/tasks/{taskId}:subscribe` (SSE 재연결) 엔드포인트를 구현하여 A2A 프로토콜 3종 호출 모드 (블로킹/폴링/스트리밍) 를 완성한다.

**Architecture:** Phase 2 까지 구축된 `TaskEventBusPort.subscribe(taskId, fromStreamId, listener)` 백본을 재사용한다. `StreamMessageService` 는 `SendMessageService` 와 거의 동일한 전처리(Mongo save → publish submitted → Kafka publish) 를 수행하지만 `await` 대신 `subscribe(fromId="0")` 로 구독하여 각 이벤트를 `SseEmitter.send()` 로 흘려보낸다. `SubscribeTaskService` 는 MongoDB 로 Task 존재/권한을 확인한 뒤 **Redis Stream 생존 여부**(터미널+grace 내부면 존재, 경과 후면 소멸)를 체크하여 살아있으면 `subscribe(fromId=Last-Event-ID ?: "0")` 로 재구독, 소멸했으면 `STREAM_UNSUPPORTED(-32067)` 로 410 GONE 을 반환한다. SSE 하트비트 스케줄러가 idle 연결을 15초 간격으로 유지한다(`SseEmitter.send(SseEmitter.event().comment("keepalive"))`). `final:true` 이벤트 수신 시 `emitter.complete()` 를 호출하고 subscription.close(), `eventBus.close(taskId)` 는 `ResultConsumerAdapter` 가 기존처럼 호출한다(중복 호출 방지).

**Tech Stack:** Kotlin 1.9 / Spring Boot 3 / Spring MVC (`SseEmitter` + async servlet) / Redis Stream (`TaskEventBusPort.subscribe`) / Kafka (기존) / MongoDB (기존). 테스트: JUnit 5 + mockk + Spring `@WebMvcTest` (asyncDispatch for SSE) + Testcontainers e2e. SSE 클라이언트는 `OkHttp` `EventSources` (Testcontainers 테스트 이미 `RestTemplate` 만 쓰므로 e2e 에 OkHttp 추가 필요).

---

## Phase 3 전제

- Phase 2 merge commit 을 base 로 한다 (`develop` 머지 완료, 브랜치 `feat/api/task-processing-phase2` 의 최신 상태). `SendMessageService`(sendBlocking + sendAsync), `GetTaskService`, `TaskEventBusPort`(publish/subscribe/await/close), `RedisStreamTaskEventBus`, `EventBusPoller`, `A2AException`+`A2AErrorCodes`, `A2AExceptionHandler`, `AgentController`(sendMessage + getTask), `JsonRpcRequest/Response` envelope, `A2ATaskDto`+`A2ATaskMapper`, `ResultConsumerAdapter` 가 이미 존재한다.
- 본 Phase 는 spec `docs/superpowers/specs/2026-04-09-task-processing-design.md` 의 **시나리오 C (SSE `message:stream`)** 와 **시나리오 D (SSE 재연결 `:subscribe`)** 를 구현한다. §2.7 의 `-32067 STREAM_UNSUPPORTED`, §4.1~4.2 의 에러 계층, §4.6 의 SSE 하트비트 15초 · emitter timeout 30분 을 반영한다.
- JSON-RPC `id` 필드는 **SSE 에서도 요청↔응답 echo**. `message:stream` 은 요청 body 가 `JsonRpcRequest<SendMessageParams>` 이므로 envelope.id 를 프레임 안에 embed. `:subscribe` 는 GET 이라 envelope 이 없으므로 프레임의 `id` 는 항상 `null`.
- SSE 프레임 payload 는 `JsonRpcResponse<A2ATaskDto>` 전체. 이벤트 필드는 `event: message` (기본), `id: <Redis Stream entry id>`. 공식 SSE 스펙 `Last-Event-ID` 헤더 매핑을 위해 **프레임 `id` = Redis Stream entry id** 로 정확히 맞춘다.
- FE 는 현재 SSE 연결 UI 가 없으므로 본 Phase 에서도 FE 수정 없음. FE 채팅 UI 구현 시점에 `EventSource` 로 소비.
- Spring MVC 의 `SseEmitter` 를 사용하고 Reactor (`WebFlux`) 는 도입하지 않는다. 스레드 모델: Tomcat 서블릿 스레드에서 `SseEmitter` 반환 → 서블릿 async 로 연결 보유 → `EventBusPoller` 의 워커 스레드가 listener 콜백으로 `emitter.send()` 호출.
- `ResultConsumerAdapter` 가 이미 터미널 수신 시 `eventBus.close(taskId)` 로 60초 후 `EXPIRE` 를 건다. Phase 3 에서 `StreamMessageService` 는 `eventBus.close()` 를 **직접 호출하지 않는다** (중복 호출 시 `EXPIRE` 가 연장되지 않아 무해하지만, 책임 경계를 명확히 하기 위함).

---

## Phase 3 파일 트리 (신규/수정 요약)

### 신규 파일

```
apps/api/src/main/kotlin/com/bara/api/
├── domain/exception/
│   └── StreamUnsupportedException.kt        ← -32067 (A2AException 서브)
├── application/
│   ├── port/in/command/
│   │   └── StreamMessageUseCase.kt          ← SSE 스트리밍 UseCase port
│   ├── port/in/query/
│   │   └── SubscribeTaskQuery.kt            ← SSE 재연결 UseCase port (입력이 read-only 라 query 범주)
│   └── service/
│       ├── command/
│       │   └── StreamMessageService.kt      ← 시나리오 C 구현
│       └── query/
│           └── SubscribeTaskService.kt      ← 시나리오 D 구현
├── adapter/in/rest/
│   └── sse/
│       ├── SseBridge.kt                     ← SseEmitter ↔ Subscription 연결 + 하트비트 바인딩
│       └── SseHeartbeatScheduler.kt         ← 15초 주기로 활성 emitter 에 keepalive
└── config/
    └── (TaskProperties.kt 수정, 신규 없음)

apps/api/src/test/kotlin/com/bara/api/
├── application/service/
│   ├── command/StreamMessageServiceTest.kt  ← mockk unit (비동기 subscribe/emitter 검증)
│   └── query/SubscribeTaskServiceTest.kt    ← mockk unit (스트림 생존/만료 분기)
├── adapter/in/rest/sse/
│   ├── SseBridgeTest.kt                     ← final event → complete, error → close 검증
│   └── SseHeartbeatSchedulerTest.kt         ← awaitility 로 주기적 keepalive 검증
└── adapter/in/rest/
    └── AgentControllerStreamTest.kt         ← @WebMvcTest (asyncDispatch) — stream + subscribe

apps/api/src/e2eTest/kotlin/com/bara/api/e2e/
├── scenario/
│   └── TaskStreamingScenarioTest.kt         ← SSE 3 시나리오 (stream 완료 / 재연결 backfill / 410 만료)
└── support/
    └── SseTestClient.kt                     ← OkHttp EventSources 래핑

docs/guides/logging/flows/
└── api-task-processing.md                   ← stream/subscribe outcome 추가 (수정)
```

### 수정 파일

```
apps/api/src/main/kotlin/com/bara/api/
├── config/TaskProperties.kt                 ← emitterTimeoutMs, heartbeatIntervalMs 추가
├── domain/exception/A2AException.kt         ← A2AErrorCodes.STREAM_UNSUPPORTED = -32067
├── adapter/in/rest/
│   ├── A2AExceptionHandler.kt               ← STREAM_UNSUPPORTED → 410 GONE + outcome="stream_expired"
│   └── AgentController.kt                   ← message:stream, tasks/{id}:subscribe 엔드포인트 추가

apps/api/src/main/resources/application.yml  ← bara.api.task.emitter-timeout-ms, heartbeat-interval-ms

apps/api/build.gradle.kts                    ← e2eTest 에 okhttp-sse 추가 (이미 okhttp 있으면 artifact 추가)
```

### FE 변경 없음

### 삭제 없음

---

## Task 0: Pre-flight — 브랜치 / 빌드 / 현상 확인

**Files:**

- Read: `apps/api/build.gradle.kts`
- Read: git state

- [ ] **Step 1: develop 기반으로 새 브랜치 생성**

```bash
git fetch origin
git checkout develop
git pull origin develop
git checkout -b feat/api/task-processing-phase3
```

- [ ] **Step 2: 현재 빌드 + 기존 테스트 통과 확인 (회귀 베이스라인)**

```bash
./gradlew :apps:api:build
```

Expected: BUILD SUCCESSFUL. 실패 시 Phase 2 회귀 복구 먼저.

- [ ] **Step 3: `build.gradle.kts` 의 기존 의존성 확인**

Read `apps/api/build.gradle.kts` 에서 `okhttp` 혹은 `spring-boot-starter-web` 확인. `spring-boot-starter-web` 이 있으면 `SseEmitter` 가 이미 포함됨. e2eTest sourceSet 에 okhttp 가 없으면 Task 10 에서 추가.

- [ ] **Step 4: 베이스라인 커밋 없이 다음 Task 진행** (현재 커밋 없음)

---

## Task 1: `A2AErrorCodes.STREAM_UNSUPPORTED` + `StreamUnsupportedException`

**Files:**

- Modify: `apps/api/src/main/kotlin/com/bara/api/domain/exception/A2AException.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/domain/exception/StreamUnsupportedException.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/A2AExceptionHandler.kt`
- Test: `apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/A2AExceptionHandlerTest.kt` (기존)

- [ ] **Step 1: `A2AExceptionHandlerTest` 에 실패하는 테스트 추가 (STREAM_UNSUPPORTED → 410 + stream_expired)**

`apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/A2AExceptionHandlerTest.kt` 를 열고, 기존 test class 안에 테스트를 추가:

```kotlin
@Test
fun `StreamUnsupportedException maps to 410 GONE with stream_expired outcome`() {
    every { throwingUseCase.throwIt() } throws StreamUnsupportedException("stream expired")

    mockMvc.perform(get("/__probe/throw"))
        .andExpect(status().isGone)
        .andExpect(jsonPath("$.error.code").value(A2AErrorCodes.STREAM_UNSUPPORTED))
        .andExpect(jsonPath("$.error.message").value("stream expired"))
}
```

> 만약 기존 `A2AExceptionHandlerTest` 가 `@WebMvcTest` + stub controller 패턴으로 작성되어 있다면 그 패턴을 따라간다. 없으면 Phase 2 에서 작성된 파일 구조를 그대로 맞춰 작성. 실제 파일을 먼저 읽어 테스트 스타일을 확인할 것.

- [ ] **Step 2: 테스트 실행 → 컴파일 실패 확인 (`StreamUnsupportedException`, `A2AErrorCodes.STREAM_UNSUPPORTED` 미정의)**

```bash
./gradlew :apps:api:test --tests "com.bara.api.adapter.in.rest.A2AExceptionHandlerTest"
```

Expected: COMPILATION FAILED with `unresolved reference: StreamUnsupportedException` 또는 `STREAM_UNSUPPORTED`.

- [ ] **Step 3: `A2AException.kt` 에 `STREAM_UNSUPPORTED = -32067` 추가**

`apps/api/src/main/kotlin/com/bara/api/domain/exception/A2AException.kt` 의 `A2AErrorCodes` object 에 상수를 추가:

```kotlin
object A2AErrorCodes {
    const val KAFKA_PUBLISH_FAILED = -32001
    const val AGENT_UNAVAILABLE = -32062
    const val AGENT_TIMEOUT = -32063
    const val TASK_NOT_FOUND = -32064
    const val TASK_ACCESS_DENIED = -32065
    const val STREAM_UNSUPPORTED = -32067
}
```

- [ ] **Step 4: `StreamUnsupportedException` 신규 클래스 작성**

새 파일 `apps/api/src/main/kotlin/com/bara/api/domain/exception/StreamUnsupportedException.kt`:

```kotlin
package com.bara.api.domain.exception

/**
 * SSE 재연결(`:subscribe`) 시점에 Redis Stream 이 이미 만료된 경우.
 * 클라이언트는 `GET /tasks/{id}` 폴링으로 전환해야 한다.
 * HTTP 410 GONE + JSON-RPC `-32067` 로 매핑.
 */
class StreamUnsupportedException(
    message: String = "Task stream is no longer available",
) : A2AException(A2AErrorCodes.STREAM_UNSUPPORTED, message)
```

- [ ] **Step 5: `A2AExceptionHandler` 의 `httpFor` / `outcomeFor` 에 STREAM_UNSUPPORTED 분기 추가**

`apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/A2AExceptionHandler.kt` 두 함수 수정:

```kotlin
private fun httpFor(code: Int): HttpStatus = when (code) {
    A2AErrorCodes.KAFKA_PUBLISH_FAILED -> HttpStatus.BAD_GATEWAY
    A2AErrorCodes.AGENT_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE
    A2AErrorCodes.AGENT_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT
    A2AErrorCodes.TASK_NOT_FOUND -> HttpStatus.NOT_FOUND
    A2AErrorCodes.TASK_ACCESS_DENIED -> HttpStatus.FORBIDDEN
    A2AErrorCodes.STREAM_UNSUPPORTED -> HttpStatus.GONE
    else -> HttpStatus.INTERNAL_SERVER_ERROR
}

private fun outcomeFor(code: Int): String = when (code) {
    A2AErrorCodes.KAFKA_PUBLISH_FAILED -> "kafka_publish_failed"
    A2AErrorCodes.AGENT_UNAVAILABLE -> "agent_unavailable"
    A2AErrorCodes.AGENT_TIMEOUT -> "agent_timeout"
    A2AErrorCodes.TASK_NOT_FOUND -> "task_not_found"
    A2AErrorCodes.TASK_ACCESS_DENIED -> "task_access_denied"
    A2AErrorCodes.STREAM_UNSUPPORTED -> "stream_expired"
    else -> "a2a_error"
}
```

- [ ] **Step 6: 테스트 재실행 → 통과 확인**

```bash
./gradlew :apps:api:test --tests "com.bara.api.adapter.in.rest.A2AExceptionHandlerTest"
```

Expected: BUILD SUCCESSFUL. 새 테스트 포함 전체 통과.

- [ ] **Step 7: Commit**

```bash
git add apps/api/src/main/kotlin/com/bara/api/domain/exception/A2AException.kt \
        apps/api/src/main/kotlin/com/bara/api/domain/exception/StreamUnsupportedException.kt \
        apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/A2AExceptionHandler.kt \
        apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/A2AExceptionHandlerTest.kt
git commit -m "feat(api): add StreamUnsupportedException with -32067 for expired SSE streams"
```

---

## Task 2: `TaskProperties` 에 SSE 설정 추가

**Files:**

- Modify: `apps/api/src/main/kotlin/com/bara/api/config/TaskProperties.kt`
- Modify: `apps/api/src/main/resources/application.yml`
- Test: `apps/api/src/test/kotlin/com/bara/api/config/TaskPropertiesTest.kt` (신규)

- [ ] **Step 1: `TaskPropertiesTest` 작성 (신규)**

새 파일 `apps/api/src/test/kotlin/com/bara/api/config/TaskPropertiesTest.kt`:

```kotlin
package com.bara.api.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

@SpringBootTest(classes = [TaskPropertiesTest.Config::class])
@TestPropertySource(
    properties = [
        "bara.api.task.block-timeout-seconds=10",
        "bara.api.task.kafka-publish-timeout-seconds=3",
        "bara.api.task.stream-grace-period-seconds=90",
        "bara.api.task.mongo-ttl-days=14",
        "bara.api.task.emitter-timeout-ms=600000",
        "bara.api.task.heartbeat-interval-ms=10000",
    ]
)
class TaskPropertiesTest {

    @org.springframework.boot.autoconfigure.SpringBootApplication
    @EnableConfigurationProperties(TaskProperties::class)
    class Config

    @org.springframework.beans.factory.annotation.Autowired
    lateinit var properties: TaskProperties

    @Test
    fun `binds all task properties including new SSE fields`() {
        assertThat(properties.blockTimeoutSeconds).isEqualTo(10)
        assertThat(properties.kafkaPublishTimeoutSeconds).isEqualTo(3)
        assertThat(properties.streamGracePeriodSeconds).isEqualTo(90)
        assertThat(properties.mongoTtlDays).isEqualTo(14)
        assertThat(properties.emitterTimeoutMs).isEqualTo(600_000)
        assertThat(properties.heartbeatIntervalMs).isEqualTo(10_000)
    }
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

```bash
./gradlew :apps:api:test --tests "com.bara.api.config.TaskPropertiesTest"
```

Expected: COMPILATION FAILED with `unresolved reference: emitterTimeoutMs`.

- [ ] **Step 3: `TaskProperties` 수정**

`apps/api/src/main/kotlin/com/bara/api/config/TaskProperties.kt`:

```kotlin
package com.bara.api.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "bara.api.task")
data class TaskProperties(
    val blockTimeoutSeconds: Long = 30,
    val kafkaPublishTimeoutSeconds: Long = 5,
    val streamGracePeriodSeconds: Long = 60,
    val mongoTtlDays: Long = 7,
    val emitterTimeoutMs: Long = 30 * 60 * 1000, // 30분
    val heartbeatIntervalMs: Long = 15 * 1000,   // 15초
)
```

- [ ] **Step 4: `application.yml` 에 기본 override 추가**

`apps/api/src/main/resources/application.yml` 의 `bara.api.task` 블록:

```yaml
bara:
  api:
    task:
      block-timeout-seconds: 30
      kafka-publish-timeout-seconds: 5
      stream-grace-period-seconds: 60
      mongo-ttl-days: 7
      emitter-timeout-ms: 1800000 # 30분
      heartbeat-interval-ms: 15000 # 15초
```

- [ ] **Step 5: 테스트 재실행 → 통과 확인**

```bash
./gradlew :apps:api:test --tests "com.bara.api.config.TaskPropertiesTest"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/kotlin/com/bara/api/config/TaskProperties.kt \
        apps/api/src/main/resources/application.yml \
        apps/api/src/test/kotlin/com/bara/api/config/TaskPropertiesTest.kt
git commit -m "feat(api): add SSE emitter timeout and heartbeat interval properties"
```

---

## Task 3: `SseBridge` — SseEmitter ↔ Subscription 리소스 바인딩

**Files:**

- Create: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/sse/SseBridge.kt`
- Test: `apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/sse/SseBridgeTest.kt`

**설계 의도:** `StreamMessageService` 와 `SubscribeTaskService` 는 **동일한 emitter 관리 로직** 을 공유한다. 둘 다 `SseEmitter` 를 만들고, `TaskEventBusPort.subscribe()` 로 Subscription 을 받고, 각 이벤트를 `emitter.send()` 로 전송하며, `final:true` / `onCompletion` / `onError` / `onTimeout` 중 어느 하나가 트리거되면 Subscription 을 닫고 emitter 를 complete 한다. 이 공통 로직을 `SseBridge` 에 캡슐화한다. `SseHeartbeatScheduler` 는 활성 emitter 집합을 `SseBridge` 에서 가져가 keepalive 를 보낸다.

- [ ] **Step 1: `SseBridgeTest` 작성 (실패)**

새 파일 `apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/sse/SseBridgeTest.kt`:

```kotlin
package com.bara.api.adapter.`in`.rest.sse

import com.bara.api.adapter.`in`.rest.a2a.A2ATaskDto
import com.bara.api.adapter.`in`.rest.a2a.A2ATaskStatusDto
import com.bara.api.adapter.`in`.rest.a2a.JsonRpcResponse
import com.bara.api.application.port.out.Subscription
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.TextNode
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.atomic.AtomicInteger

class SseBridgeTest {

    private val objectMapper = ObjectMapper()

    @Test
    fun `attach registers emitter and send emits frame with entry id`() {
        val emitter = SseEmitter()
        val bridge = SseBridge(objectMapper)
        val subscription = mockk<Subscription>(relaxed = true)
        val taskId = "task-1"

        val sent = AtomicInteger(0)
        emitter.onCompletion { sent.incrementAndGet() }

        bridge.attach(
            taskId = taskId,
            envelopeId = TextNode.valueOf("req-1"),
            emitter = emitter,
            subscription = subscription,
        )

        val dto = sampleDto(taskId, "working", false)
        bridge.send(taskId, entryId = "1712665200000-0", dto = dto, final = false)

        assertThat(bridge.activeCount()).isEqualTo(1)
        // complete 는 final=true 시에만
        assertThat(sent.get()).isZero
    }

    @Test
    fun `send with final true completes emitter and closes subscription`() {
        val emitter = SseEmitter()
        val bridge = SseBridge(objectMapper)
        val subscription = mockk<Subscription>(relaxed = true)
        val taskId = "task-2"

        bridge.attach(taskId, null, emitter, subscription)
        val dto = sampleDto(taskId, "completed", true)
        bridge.send(taskId, entryId = "1712665200001-0", dto = dto, final = true)

        verify { subscription.close() }
        assertThat(bridge.activeCount()).isZero
    }

    @Test
    fun `onError releases subscription and removes registration`() {
        val emitter = SseEmitter()
        val bridge = SseBridge(objectMapper)
        val subscription = mockk<Subscription>(relaxed = true)
        val taskId = "task-3"

        bridge.attach(taskId, null, emitter, subscription)
        emitter.completeWithError(RuntimeException("client gone"))

        verify { subscription.close() }
        assertThat(bridge.activeCount()).isZero
    }

    @Test
    fun `heartbeat delivers keepalive comment to active emitters`() {
        val emitter = SseEmitter()
        val bridge = SseBridge(objectMapper)
        val subscription = mockk<Subscription>(relaxed = true)
        bridge.attach("t", null, emitter, subscription)

        bridge.heartbeat()
        // emitter.send 내부 호출은 예외가 없으면 OK. 커버리지 목적.
        assertThat(bridge.activeCount()).isEqualTo(1)
    }

    private fun sampleDto(id: String, state: String, @Suppress("UNUSED_PARAMETER") final: Boolean) = A2ATaskDto(
        id = id,
        contextId = "ctx",
        status = A2ATaskStatusDto(state = state, timestamp = "2026-04-20T00:00:00Z"),
    )
}
```

- [ ] **Step 2: 테스트 실행 → 컴파일 실패 확인**

```bash
./gradlew :apps:api:test --tests "com.bara.api.adapter.in.rest.sse.SseBridgeTest"
```

Expected: `unresolved reference: SseBridge`.

- [ ] **Step 3: `SseBridge` 구현**

새 파일 `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/sse/SseBridge.kt`:

```kotlin
package com.bara.api.adapter.`in`.rest.sse

import com.bara.api.adapter.`in`.rest.a2a.A2ATaskDto
import com.bara.api.adapter.`in`.rest.a2a.JsonRpcResponse
import com.bara.api.application.port.out.Subscription
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * SSE 응답을 관리하는 브릿지.
 *
 * - `attach` 로 (taskId, emitter, subscription) 쌍을 등록한다.
 * - `send` 는 이벤트를 emitter 에 전달하고, final=true 면 자동 release.
 * - `onCompletion`/`onTimeout`/`onError` 콜백이 트리거되면 Subscription 을 닫고 등록을 해제한다.
 * - `heartbeat` 는 활성 emitter 전체에 keepalive comment 를 전송한다.
 *
 * 스레드 안전: emitter.send()/complete() 는 Spring 이 내부 lock 으로 보호한다.
 * 활성 map 은 ConcurrentHashMap.
 */
@Component
class SseBridge(
    private val objectMapper: ObjectMapper,
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val active = ConcurrentHashMap<String, Entry>()

    fun attach(
        taskId: String,
        envelopeId: JsonNode?,
        emitter: SseEmitter,
        subscription: Subscription,
    ) {
        val entry = Entry(emitter, subscription, envelopeId)
        active[taskId] = entry

        emitter.onCompletion { release(taskId, "completion") }
        emitter.onTimeout {
            logger.debug("SSE emitter timeout taskId={}", taskId)
            emitter.complete()
            release(taskId, "timeout")
        }
        emitter.onError { e ->
            logger.debug("SSE emitter error taskId={}: {}", taskId, e.message)
            release(taskId, "error")
        }
    }

    fun send(taskId: String, entryId: String, dto: A2ATaskDto, final: Boolean) {
        val entry = active[taskId] ?: return
        val envelope = JsonRpcResponse(id = entry.envelopeId, result = dto)
        try {
            val event = SseEmitter.event()
                .id(entryId)
                .name("message")
                .data(objectMapper.writeValueAsString(envelope))
            entry.emitter.send(event)
            if (final) {
                entry.emitter.complete()
                release(taskId, "final")
            }
        } catch (e: IOException) {
            logger.debug("SSE send failed (client likely disconnected) taskId={}", taskId)
            release(taskId, "io-error")
        } catch (e: IllegalStateException) {
            // emitter 이미 complete 상태
            release(taskId, "emitter-closed")
        }
    }

    fun heartbeat() {
        active.forEach { (taskId, entry) ->
            try {
                entry.emitter.send(SseEmitter.event().comment("keepalive"))
            } catch (e: Exception) {
                logger.debug("heartbeat failed taskId={}: {}", taskId, e.message)
                release(taskId, "heartbeat-fail")
            }
        }
    }

    fun activeCount(): Int = active.size

    private fun release(taskId: String, reason: String) {
        active.remove(taskId)?.also {
            try { it.subscription.close() } catch (_: Throwable) {}
        }
        logger.trace("SSE released taskId={} reason={}", taskId, reason)
    }

    private data class Entry(
        val emitter: SseEmitter,
        val subscription: Subscription,
        val envelopeId: JsonNode?,
    )
}
```

- [ ] **Step 4: 테스트 재실행 → 통과 확인**

```bash
./gradlew :apps:api:test --tests "com.bara.api.adapter.in.rest.sse.SseBridgeTest"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/sse/SseBridge.kt \
        apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/sse/SseBridgeTest.kt
git commit -m "feat(api): add SseBridge to manage emitter-subscription lifecycle"
```

---

## Task 4: `SseHeartbeatScheduler` — 15초 주기 keepalive

**Files:**

- Create: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/sse/SseHeartbeatScheduler.kt`
- Test: `apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/sse/SseHeartbeatSchedulerTest.kt`

- [ ] **Step 1: 테스트 작성 (실패)**

새 파일 `apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/sse/SseHeartbeatSchedulerTest.kt`:

```kotlin
package com.bara.api.adapter.`in`.rest.sse

import com.bara.api.config.TaskProperties
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SseHeartbeatSchedulerTest {

    @Test
    fun `scheduler invokes bridge heartbeat at configured interval`() {
        val bridge = mockk<SseBridge>(relaxed = true)
        val props = TaskProperties(heartbeatIntervalMs = 100)
        val executor = Executors.newSingleThreadScheduledExecutor()

        val scheduler = SseHeartbeatScheduler(bridge, props, executor)
        scheduler.start()

        executor.schedule({}, 350, TimeUnit.MILLISECONDS).get()
        scheduler.stop()

        verify(atLeast = 3) { bridge.heartbeat() }
        executor.shutdownNow()
    }
}
```

- [ ] **Step 2: 실행 → 실패**

```bash
./gradlew :apps:api:test --tests "com.bara.api.adapter.in.rest.sse.SseHeartbeatSchedulerTest"
```

- [ ] **Step 3: 구현 작성**

새 파일 `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/sse/SseHeartbeatScheduler.kt`:

```kotlin
package com.bara.api.adapter.`in`.rest.sse

import com.bara.api.config.TaskProperties
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Component
class SseHeartbeatScheduler(
    private val bridge: SseBridge,
    private val properties: TaskProperties,
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "sse-heartbeat").apply { isDaemon = true }
    },
) {

    @Volatile private var scheduled: ScheduledFuture<*>? = null

    @PostConstruct
    fun start() {
        val interval = properties.heartbeatIntervalMs
        scheduled = executor.scheduleAtFixedRate(
            { runCatching { bridge.heartbeat() } },
            interval, interval, TimeUnit.MILLISECONDS,
        )
    }

    @PreDestroy
    fun stop() {
        scheduled?.cancel(false)
        scheduled = null
    }
}
```

- [ ] **Step 4: 테스트 재실행 → 통과**

```bash
./gradlew :apps:api:test --tests "com.bara.api.adapter.in.rest.sse.SseHeartbeatSchedulerTest"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/sse/SseHeartbeatScheduler.kt \
        apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/sse/SseHeartbeatSchedulerTest.kt
git commit -m "feat(api): add SseHeartbeatScheduler to keep idle SSE connections alive"
```

---

## Task 5: `StreamMessageUseCase` 포트

**Files:**

- Create: `apps/api/src/main/kotlin/com/bara/api/application/port/in/command/StreamMessageUseCase.kt`

- [ ] **Step 1: 포트 인터페이스 작성**

새 파일 `apps/api/src/main/kotlin/com/bara/api/application/port/in/command/StreamMessageUseCase.kt`:

```kotlin
package com.bara.api.application.port.`in`.command

import com.bara.api.adapter.`in`.rest.sse.SseBridge
import com.fasterxml.jackson.databind.JsonNode
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

interface StreamMessageUseCase {

    /**
     * 새 태스크를 시작하고 SSE 로 진행 상태를 스트리밍한다.
     * 반환된 emitter 는 컨트롤러가 그대로 리턴 → Spring MVC 가 async response 유지.
     *
     * 구현은 내부적으로:
     *  1. Registry 에서 agentId 확인 (없으면 AgentUnavailableException)
     *  2. Task(submitted) save + EventBus publish submitted
     *  3. EventBus.subscribe(taskId, "0", listener) → Subscription
     *  4. listener 에서 SseBridge.send(taskId, entryId, dto, final)
     *  5. SseBridge.attach(taskId, envelopeId, emitter, subscription)
     *  6. Kafka publish (실패 시 Task failed 전환 + EventBus publish failed → subscriber 로 전달되어 emitter 종료)
     */
    fun stream(
        userId: String,
        agentName: String,
        envelopeId: JsonNode?,
        request: SendMessageUseCase.SendMessageRequest,
    ): SseEmitter
}
```

> `SendMessageRequest` 는 Phase 2 에서 이미 `SendMessageUseCase` 안에 정의되어 재사용한다. `SseBridge` 는 어댑터 레이어라 포트에서 직접 사용하지는 않지만 `SseEmitter` 반환은 Spring MVC 통합용으로 허용한다 (Phase 2 에서 `CompletableFuture<A2ATaskDto>` 반환을 허용한 패턴과 동일).

- [ ] **Step 2: 컴파일 확인**

```bash
./gradlew :apps:api:compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add apps/api/src/main/kotlin/com/bara/api/application/port/in/command/StreamMessageUseCase.kt
git commit -m "feat(api): add StreamMessageUseCase port for SSE streaming"
```

---

## Task 6: `StreamMessageService` — 시나리오 C 구현

**Files:**

- Create: `apps/api/src/main/kotlin/com/bara/api/application/service/command/StreamMessageService.kt`
- Test: `apps/api/src/test/kotlin/com/bara/api/application/service/command/StreamMessageServiceTest.kt`

- [ ] **Step 1: 테스트 작성 (실패)**

새 파일 `apps/api/src/test/kotlin/com/bara/api/application/service/command/StreamMessageServiceTest.kt`:

```kotlin
package com.bara.api.application.service.command

import com.bara.api.adapter.`in`.rest.sse.SseBridge
import com.bara.api.application.port.`in`.command.SendMessageUseCase
import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.Subscription
import com.bara.api.application.port.out.TaskEventBusPort
import com.bara.api.application.port.out.TaskPublisherPort
import com.bara.api.application.port.out.TaskRepositoryPort
import com.bara.api.config.TaskProperties
import com.bara.api.domain.exception.AgentUnavailableException
import com.bara.api.domain.exception.KafkaPublishException
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskEvent
import com.bara.api.domain.model.TaskState
import com.fasterxml.jackson.databind.node.TextNode
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant

class StreamMessageServiceTest {

    private val registry = mockk<AgentRegistryPort>()
    private val publisher = mockk<TaskPublisherPort>()
    private val repo = mockk<TaskRepositoryPort>(relaxed = true)
    private val bus = mockk<TaskEventBusPort>()
    private val bridge = mockk<SseBridge>(relaxed = true)
    private val properties = TaskProperties()
    private lateinit var service: StreamMessageService

    @BeforeEach
    fun setup() {
        service = StreamMessageService(registry, publisher, repo, bus, bridge, properties)
    }

    @Test
    fun `stream returns emitter and wires subscription before kafka publish`() {
        every { registry.getAgentId("agent-a") } returns "agent-id-1"
        val publishedEntryId = "1712665200000-0"
        every { bus.publish(any(), any()) } returns publishedEntryId

        val listenerSlot = slot<(TaskEvent) -> Unit>()
        val subscription = mockk<Subscription>(relaxed = true)
        every { bus.subscribe(any(), "0", capture(listenerSlot)) } returns subscription
        justRun { publisher.publish(any(), any()) }

        val req = SendMessageUseCase.SendMessageRequest(
            messageId = "msg-1", text = "hi", contextId = null,
        )

        val emitter = service.stream("user-1", "agent-a", TextNode.valueOf("rpc-1"), req)

        assertThat(emitter).isNotNull
        verify(ordering = io.mockk.Ordering.SEQUENCE) {
            registry.getAgentId("agent-a")
            repo.save(any())
            bus.publish(any(), match { it.state == TaskState.SUBMITTED })
            bus.subscribe(any(), "0", any())
            bridge.attach(any(), any(), any(), subscription)
            publisher.publish("agent-id-1", any())
        }
    }

    @Test
    fun `unknown agent raises AgentUnavailableException before any side effects`() {
        every { registry.getAgentId("nope") } returns null

        val req = SendMessageUseCase.SendMessageRequest(messageId = "m", text = "x", contextId = null)

        assertThatThrownBy {
            service.stream("user", "nope", null, req)
        }.isInstanceOf(AgentUnavailableException::class.java)

        verify(exactly = 0) { repo.save(any()) }
        verify(exactly = 0) { bus.subscribe(any(), any(), any()) }
    }

    @Test
    fun `kafka failure transitions task to failed and publishes failed event`() {
        every { registry.getAgentId(any()) } returns "agent-id"
        every { bus.publish(any(), any()) } returns "1-0"
        val subscription = mockk<Subscription>(relaxed = true)
        every { bus.subscribe(any(), "0", any()) } returns subscription
        every { publisher.publish(any(), any()) } throws KafkaPublishException("down")

        val req = SendMessageUseCase.SendMessageRequest("m", "x", null)

        assertThatThrownBy {
            service.stream("u", "a", null, req)
        }.isInstanceOf(KafkaPublishException::class.java)

        verify { repo.updateState(any(), TaskState.FAILED, any(), any(), any(), any(), any(), any(), any()) }
        verify { bus.publish(any(), match { it.state == TaskState.FAILED && it.final }) }
    }
}
```

- [ ] **Step 2: 실행 → 실패**

```bash
./gradlew :apps:api:test --tests "com.bara.api.application.service.command.StreamMessageServiceTest"
```

- [ ] **Step 3: `StreamMessageService` 구현**

새 파일 `apps/api/src/main/kotlin/com/bara/api/application/service/command/StreamMessageService.kt`:

```kotlin
package com.bara.api.application.service.command

import com.bara.api.adapter.`in`.rest.a2a.A2ATaskMapper
import com.bara.api.adapter.`in`.rest.sse.SseBridge
import com.bara.api.application.port.`in`.command.SendMessageUseCase
import com.bara.api.application.port.`in`.command.StreamMessageUseCase
import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.TaskEventBusPort
import com.bara.api.application.port.out.TaskMessagePayload
import com.bara.api.application.port.out.TaskPublisherPort
import com.bara.api.application.port.out.TaskRepositoryPort
import com.bara.api.config.TaskProperties
import com.bara.api.domain.exception.AgentUnavailableException
import com.bara.api.domain.exception.KafkaPublishException
import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.A2APart
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskEvent
import com.bara.api.domain.model.TaskState
import com.bara.common.logging.WideEvent
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Service
class StreamMessageService(
    private val agentRegistryPort: AgentRegistryPort,
    private val taskPublisherPort: TaskPublisherPort,
    private val taskRepositoryPort: TaskRepositoryPort,
    private val taskEventBusPort: TaskEventBusPort,
    private val sseBridge: SseBridge,
    private val properties: TaskProperties,
) : StreamMessageUseCase {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun stream(
        userId: String,
        agentName: String,
        envelopeId: JsonNode?,
        request: SendMessageUseCase.SendMessageRequest,
    ): SseEmitter {
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

        // ① Mongo insert + ② EventBus publish submitted (Phase 1/2 동일)
        taskRepositoryPort.save(task)
        taskEventBusPort.publish(taskId, TaskEvent.of(task))

        // ③ SseEmitter + Subscription 선 등록 (fromId="0" 으로 backfill)
        val emitter = SseEmitter(properties.emitterTimeoutMs)
        val entryIdRef = java.util.concurrent.atomic.AtomicReference<String>("0")
        val subscription = taskEventBusPort.subscribe(taskId, "0") { event ->
            // Redis Stream entry id 를 EventBusPoller 가 lastId 로 관리하므로
            // 여기서는 last 갱신이 불가능. 따라서 정확한 id 매핑을 위해
            // send 시점에 Redis 에 저장된 id 를 다시 받아오긴 어렵다.
            // 대안: poller 가 listener 시그니처를 `(entryId, event) -> Unit` 로 확장.
            // 본 플랜에서는 Task 10 에서 EventBusPoller 시그니처 확장을 수행한다.
            val id = entryIdRef.get()
            val dto = A2ATaskMapper.fromEvent(task, event)
            sseBridge.send(taskId, id, dto, event.final)
        }
        sseBridge.attach(taskId, envelopeId, emitter, subscription)

        // ④ Kafka publish — 실패 시 failed 전환
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
            WideEvent.put("outcome", "kafka_publish_failed")
            WideEvent.message("Kafka publish 실패 (stream)")
            throw e
        }

        WideEvent.put("task_id", taskId)
        WideEvent.put("agent_name", agentName)
        WideEvent.put("agent_id", agentId)
        WideEvent.put("user_id", userId)
        WideEvent.put("outcome", "stream_started")
        WideEvent.message("태스크 스트리밍 시작")

        return emitter
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
}
```

**주의** — 위 구현에는 "entry id 매핑" 문제가 남아 있다: `EventBusPoller` 가 현재 listener 에 `TaskEvent` 만 넘기고 `entryId` 는 내부에만 보관한다. SSE 프레임의 `id:` 필드는 Redis Stream entry id 여야 `Last-Event-ID` 재연결이 정확히 오프셋으로 매핑된다. 이를 고치는 것이 **Task 10** 이다.

- [ ] **Step 4: 컴파일 + 테스트 통과 확인**

```bash
./gradlew :apps:api:test --tests "com.bara.api.application.service.command.StreamMessageServiceTest"
```

Expected: PASS. 테스트는 시퀀스와 예외 분기만 검증하고 entry id 정확성은 Task 10 에서 보강.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/kotlin/com/bara/api/application/service/command/StreamMessageService.kt \
        apps/api/src/test/kotlin/com/bara/api/application/service/command/StreamMessageServiceTest.kt
git commit -m "feat(api): add StreamMessageService for SSE message streaming"
```

---

## Task 7: `SubscribeTaskQuery` 포트 + `SubscribeTaskService` — 시나리오 D

**Files:**

- Create: `apps/api/src/main/kotlin/com/bara/api/application/port/in/query/SubscribeTaskQuery.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/application/service/query/SubscribeTaskService.kt`
- Test: `apps/api/src/test/kotlin/com/bara/api/application/service/query/SubscribeTaskServiceTest.kt`

**설계 상세:**

- `TaskRepositoryPort.findByIdAndUserId(taskId, userId)` 로 존재/권한 확인 (Phase 2 `GetTaskService` 와 동일 로직).
- 스트림 생존 체크: `RedisStreamTaskEventBus` 에 `fun streamExists(taskId: String): Boolean` 신규 추가. 내부적으로 `redisTemplate.hasKey("stream:task:$taskId")`.
- `TaskEventBusPort` 포트 인터페이스에 `fun streamExists(taskId: String): Boolean` 추가.

- [ ] **Step 1: `TaskEventBusPort` 에 `streamExists` 추가 + 어댑터 구현**

Read and modify `apps/api/src/main/kotlin/com/bara/api/application/port/out/TaskEventBusPort.kt`, 인터페이스에 다음 추가:

```kotlin
/** 스트림 키가 Redis 에 아직 존재하는지. grace period 내면 true. */
fun streamExists(taskId: String): Boolean
```

Modify `apps/api/src/main/kotlin/com/bara/api/adapter/out/redis/RedisStreamTaskEventBus.kt`, 함수 추가:

```kotlin
override fun streamExists(taskId: String): Boolean =
    redisTemplate.hasKey(streamKey(taskId))
```

- [ ] **Step 2: 서비스 테스트 작성**

새 파일 `apps/api/src/test/kotlin/com/bara/api/application/service/query/SubscribeTaskServiceTest.kt`:

```kotlin
package com.bara.api.application.service.query

import com.bara.api.adapter.`in`.rest.sse.SseBridge
import com.bara.api.application.port.out.Subscription
import com.bara.api.application.port.out.TaskEventBusPort
import com.bara.api.application.port.out.TaskRepositoryPort
import com.bara.api.config.TaskProperties
import com.bara.api.domain.exception.StreamUnsupportedException
import com.bara.api.domain.exception.TaskAccessDeniedException
import com.bara.api.domain.exception.TaskNotFoundException
import com.bara.api.domain.model.A2AMessage
import com.bara.api.domain.model.A2APart
import com.bara.api.domain.model.Task
import com.bara.api.domain.model.TaskState
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class SubscribeTaskServiceTest {

    private val repo = mockk<TaskRepositoryPort>()
    private val bus = mockk<TaskEventBusPort>()
    private val bridge = mockk<SseBridge>(relaxed = true)
    private val properties = TaskProperties(emitterTimeoutMs = 60_000)
    private val service = SubscribeTaskService(repo, bus, bridge, properties)

    @Test
    fun `unknown task throws TaskNotFoundException`() {
        every { repo.findById("t1") } returns null

        assertThatThrownBy { service.subscribe("u1", "t1", null) }
            .isInstanceOf(TaskNotFoundException::class.java)
    }

    @Test
    fun `other user denied`() {
        every { repo.findById("t1") } returns sampleTask("t1", "owner", TaskState.WORKING)

        assertThatThrownBy { service.subscribe("intruder", "t1", null) }
            .isInstanceOf(TaskAccessDeniedException::class.java)
    }

    @Test
    fun `terminal task with expired stream raises StreamUnsupportedException`() {
        every { repo.findById("t1") } returns sampleTask("t1", "u1", TaskState.COMPLETED)
        every { bus.streamExists("t1") } returns false

        assertThatThrownBy { service.subscribe("u1", "t1", null) }
            .isInstanceOf(StreamUnsupportedException::class.java)
    }

    @Test
    fun `active stream returns emitter and subscribes from Last-Event-ID`() {
        every { repo.findById("t1") } returns sampleTask("t1", "u1", TaskState.WORKING)
        every { bus.streamExists("t1") } returns true
        val subscription = mockk<Subscription>(relaxed = true)
        every { bus.subscribe("t1", "100-0", any()) } returns subscription

        val emitter = service.subscribe("u1", "t1", lastEventId = "100-0")

        assertThat(emitter).isNotNull
        verify { bridge.attach("t1", null, emitter, subscription) }
    }

    @Test
    fun `missing Last-Event-ID defaults to zero`() {
        every { repo.findById("t1") } returns sampleTask("t1", "u1", TaskState.WORKING)
        every { bus.streamExists("t1") } returns true
        val subscription = mockk<Subscription>(relaxed = true)
        every { bus.subscribe("t1", "0", any()) } returns subscription

        service.subscribe("u1", "t1", lastEventId = null)

        verify { bus.subscribe("t1", "0", any()) }
    }

    private fun sampleTask(id: String, uid: String, state: TaskState): Task {
        val now = Instant.now()
        return Task(
            id = id,
            agentId = "agent",
            agentName = "agent-a",
            userId = uid,
            contextId = "ctx",
            state = state,
            inputMessage = A2AMessage("m", "user", listOf(A2APart("text", "hi"))),
            requestId = "req",
            createdAt = now,
            updatedAt = now,
            expiredAt = now.plusSeconds(3600),
        )
    }
}
```

- [ ] **Step 3: 테스트 실행 → 실패 확인**

```bash
./gradlew :apps:api:test --tests "com.bara.api.application.service.query.SubscribeTaskServiceTest"
```

- [ ] **Step 4: `SubscribeTaskQuery` 포트 작성**

새 파일 `apps/api/src/main/kotlin/com/bara/api/application/port/in/query/SubscribeTaskQuery.kt`:

```kotlin
package com.bara.api.application.port.`in`.query

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

interface SubscribeTaskQuery {
    /**
     * 기존 태스크에 대한 SSE 재연결.
     *
     * @param lastEventId SSE 표준 `Last-Event-ID` 헤더. Redis Stream entry id 와 동일 포맷.
     *                    null 이면 "0" (처음부터 backfill).
     */
    fun subscribe(userId: String, taskId: String, lastEventId: String?): SseEmitter
}
```

- [ ] **Step 5: `SubscribeTaskService` 구현**

새 파일 `apps/api/src/main/kotlin/com/bara/api/application/service/query/SubscribeTaskService.kt`:

```kotlin
package com.bara.api.application.service.query

import com.bara.api.adapter.`in`.rest.a2a.A2ATaskMapper
import com.bara.api.adapter.`in`.rest.sse.SseBridge
import com.bara.api.application.port.`in`.query.SubscribeTaskQuery
import com.bara.api.application.port.out.TaskEventBusPort
import com.bara.api.application.port.out.TaskRepositoryPort
import com.bara.api.config.TaskProperties
import com.bara.api.domain.exception.StreamUnsupportedException
import com.bara.api.domain.exception.TaskAccessDeniedException
import com.bara.api.domain.exception.TaskNotFoundException
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@Service
class SubscribeTaskService(
    private val taskRepositoryPort: TaskRepositoryPort,
    private val taskEventBusPort: TaskEventBusPort,
    private val sseBridge: SseBridge,
    private val properties: TaskProperties,
) : SubscribeTaskQuery {

    override fun subscribe(userId: String, taskId: String, lastEventId: String?): SseEmitter {
        val task = taskRepositoryPort.findById(taskId) ?: throw TaskNotFoundException(taskId)
        if (task.userId != userId) throw TaskAccessDeniedException(taskId)

        if (task.state.isTerminal && !taskEventBusPort.streamExists(taskId)) {
            WideEvent.put("task_id", taskId)
            WideEvent.put("user_id", userId)
            WideEvent.put("outcome", "stream_expired")
            WideEvent.message("스트림 만료 — 폴링으로 전환")
            throw StreamUnsupportedException("Stream for task $taskId has expired")
        }

        val fromId = lastEventId ?: "0"
        val emitter = SseEmitter(properties.emitterTimeoutMs)

        val subscription = taskEventBusPort.subscribe(taskId, fromId) { event ->
            val dto = A2ATaskMapper.fromEvent(task, event)
            // envelope id 없음 (GET 요청이라 JSON-RPC id 미전달)
            sseBridge.send(taskId, "0", dto, event.final)
        }
        sseBridge.attach(taskId, null, emitter, subscription)

        WideEvent.put("task_id", taskId)
        WideEvent.put("user_id", userId)
        WideEvent.put("last_event_id", lastEventId ?: "null")
        WideEvent.put("outcome", "stream_resubscribed")
        WideEvent.message("스트림 재연결")

        return emitter
    }
}
```

- [ ] **Step 6: 테스트 재실행 → 통과**

```bash
./gradlew :apps:api:test --tests "com.bara.api.application.service.query.SubscribeTaskServiceTest"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add apps/api/src/main/kotlin/com/bara/api/application/port/out/TaskEventBusPort.kt \
        apps/api/src/main/kotlin/com/bara/api/adapter/out/redis/RedisStreamTaskEventBus.kt \
        apps/api/src/main/kotlin/com/bara/api/application/port/in/query/SubscribeTaskQuery.kt \
        apps/api/src/main/kotlin/com/bara/api/application/service/query/SubscribeTaskService.kt \
        apps/api/src/test/kotlin/com/bara/api/application/service/query/SubscribeTaskServiceTest.kt
git commit -m "feat(api): add SubscribeTaskService for SSE reconnect with stream-expiry guard"
```

---

## Task 8: `AgentController` 엔드포인트 추가

**Files:**

- Modify: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentController.kt`
- Test: `apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/AgentControllerStreamTest.kt` (신규)

- [ ] **Step 1: `AgentControllerStreamTest` 작성 (실패)**

새 파일 `apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/AgentControllerStreamTest.kt`:

```kotlin
package com.bara.api.adapter.`in`.rest

import com.bara.api.adapter.`in`.rest.sse.SseBridge
import com.bara.api.application.port.`in`.command.DeleteAgentUseCase
import com.bara.api.application.port.`in`.command.HeartbeatAgentUseCase
import com.bara.api.application.port.`in`.command.RegisterAgentUseCase
import com.bara.api.application.port.`in`.command.RegistryAgentUseCase
import com.bara.api.application.port.`in`.command.SendMessageUseCase
import com.bara.api.application.port.`in`.command.StreamMessageUseCase
import com.bara.api.application.port.`in`.query.GetAgentCardQuery
import com.bara.api.application.port.`in`.query.GetAgentQuery
import com.bara.api.application.port.`in`.query.GetTaskQuery
import com.bara.api.application.port.`in`.query.ListAgentsQuery
import com.bara.api.application.port.`in`.query.SubscribeTaskQuery
import com.bara.api.application.port.out.TaskPublisherPort
import com.bara.api.domain.exception.StreamUnsupportedException
import com.bara.api.domain.exception.TaskAccessDeniedException
import com.bara.api.domain.exception.TaskNotFoundException
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@WebMvcTest(controllers = [AgentController::class])
@Import(ApiExceptionHandler::class, A2AExceptionHandler::class)
@TestPropertySource(properties = [
    "spring.autoconfigure.exclude=" +
        "org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration," +
        "org.springframework.boot.autoconfigure.data.mongo.MongoDataAutoConfiguration," +
        "org.springframework.boot.autoconfigure.data.mongo.MongoRepositoriesAutoConfiguration," +
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration," +
        "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration," +
        "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration",
])
class AgentControllerStreamTest {

    @Autowired lateinit var mockMvc: MockMvc
    @MockkBean lateinit var registerAgentUseCase: RegisterAgentUseCase
    @MockkBean lateinit var deleteAgentUseCase: DeleteAgentUseCase
    @MockkBean lateinit var registryAgentUseCase: RegistryAgentUseCase
    @MockkBean lateinit var heartbeatAgentUseCase: HeartbeatAgentUseCase
    @MockkBean lateinit var sendMessageUseCase: SendMessageUseCase
    @MockkBean lateinit var streamMessageUseCase: StreamMessageUseCase
    @MockkBean lateinit var listAgentsQuery: ListAgentsQuery
    @MockkBean lateinit var getAgentQuery: GetAgentQuery
    @MockkBean lateinit var getAgentCardQuery: GetAgentCardQuery
    @MockkBean lateinit var getTaskQuery: GetTaskQuery
    @MockkBean lateinit var subscribeTaskQuery: SubscribeTaskQuery
    @MockkBean lateinit var taskPublisherPort: TaskPublisherPort
    @MockkBean lateinit var sseBridge: SseBridge

    @Test
    fun `POST message-stream returns SseEmitter with text-event-stream content type`() {
        every { streamMessageUseCase.stream(any(), any(), any(), any()) } answers { SseEmitter(1000) }

        val body = """
            {"jsonrpc":"2.0","id":"req-1","method":"message/stream",
             "params":{"message":{"messageId":"m1","parts":[{"text":"hi"}]}}}
        """.trimIndent()

        mockMvc.post("/agents/agent-a/message:stream") {
            header("X-User-Id", "u1")
            contentType = MediaType.APPLICATION_JSON
            accept = MediaType.TEXT_EVENT_STREAM
            content = body
        }.andExpect {
            status { isOk() }
            content { contentType("text/event-stream") }
        }
    }

    @Test
    fun `GET tasks subscribe returns SseEmitter`() {
        every { subscribeTaskQuery.subscribe("u1", "task-1", null) } returns SseEmitter(1000)

        mockMvc.get("/agents/agent-a/tasks/task-1:subscribe") {
            header("X-User-Id", "u1")
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `GET tasks subscribe forwards Last-Event-ID header`() {
        every { subscribeTaskQuery.subscribe("u1", "task-1", "123-0") } returns SseEmitter(1000)

        mockMvc.get("/agents/agent-a/tasks/task-1:subscribe") {
            header("X-User-Id", "u1")
            header("Last-Event-ID", "123-0")
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `GET tasks subscribe returns 410 when stream expired`() {
        every { subscribeTaskQuery.subscribe("u1", "task-x", null) } throws StreamUnsupportedException("expired")

        mockMvc.get("/agents/agent-a/tasks/task-x:subscribe") {
            header("X-User-Id", "u1")
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect {
            status { isGone() }
            jsonPath("$.error.code") { value(-32067) }
        }
    }

    @Test
    fun `GET tasks subscribe returns 404 when task missing`() {
        every { subscribeTaskQuery.subscribe(any(), any(), any()) } throws TaskNotFoundException("nope")

        mockMvc.get("/agents/agent-a/tasks/nope:subscribe") {
            header("X-User-Id", "u1")
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error.code") { value(-32064) }
        }
    }

    @Test
    fun `GET tasks subscribe returns 403 when other user`() {
        every { subscribeTaskQuery.subscribe(any(), any(), any()) } throws TaskAccessDeniedException("forbidden")

        mockMvc.get("/agents/agent-a/tasks/t:subscribe") {
            header("X-User-Id", "u1")
            accept = MediaType.TEXT_EVENT_STREAM
        }.andExpect {
            status { isForbidden() }
            jsonPath("$.error.code") { value(-32065) }
        }
    }
}
```

- [ ] **Step 2: 테스트 실행 → 실패 확인**

```bash
./gradlew :apps:api:test --tests "com.bara.api.adapter.in.rest.AgentControllerStreamTest"
```

- [ ] **Step 3: `AgentController` 수정 — 의존성 및 엔드포인트 2개 추가**

Modify `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentController.kt`:

1. Constructor 에 `streamMessageUseCase: StreamMessageUseCase` 와 `subscribeTaskQuery: SubscribeTaskQuery` 추가.
2. 메서드 추가:

```kotlin
@PostMapping("/{agentName}/message:stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
fun sendMessageStream(
    @RequestHeader("X-User-Id") userId: String,
    @PathVariable agentName: String,
    @RequestBody envelope: JsonRpcRequest<SendMessageParams>,
): SseEmitter {
    val params = envelope.params
        ?: throw IllegalArgumentException("JSON-RPC params is required")
    val text = params.message.parts.firstOrNull()?.text ?: ""
    val sendRequest = SendMessageUseCase.SendMessageRequest(
        messageId = params.message.messageId,
        text = text,
        contextId = params.contextId,
    )
    return streamMessageUseCase.stream(userId, agentName, envelope.id, sendRequest)
}

@GetMapping("/{agentName}/tasks/{taskId}:subscribe", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
fun subscribeTask(
    @RequestHeader("X-User-Id") userId: String,
    @RequestHeader(value = "Last-Event-ID", required = false) lastEventId: String?,
    @PathVariable agentName: String,
    @PathVariable taskId: String,
): SseEmitter = subscribeTaskQuery.subscribe(userId, taskId, lastEventId)
```

Imports 추가: `org.springframework.http.MediaType`, `org.springframework.web.servlet.mvc.method.annotation.SseEmitter`, `com.bara.api.application.port.in.command.StreamMessageUseCase`, `com.bara.api.application.port.in.query.SubscribeTaskQuery`.

- [ ] **Step 4: 테스트 재실행 → 통과**

```bash
./gradlew :apps:api:test --tests "com.bara.api.adapter.in.rest.AgentControllerStreamTest"
./gradlew :apps:api:test --tests "com.bara.api.adapter.in.rest.AgentControllerTest"
```

Expected: 두 테스트 모두 BUILD SUCCESSFUL. (Phase 2 AgentControllerTest 는 새 MockkBean 2개를 선언해야 할 수도 있다 — 실패하면 기존 파일에도 `@MockkBean lateinit var streamMessageUseCase` / `subscribeTaskQuery` 를 추가.)

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentController.kt \
        apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/AgentControllerStreamTest.kt \
        apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/AgentControllerTest.kt
git commit -m "feat(api): add message:stream and tasks/{id}:subscribe SSE endpoints"
```

---

## Task 9: `EventBusPoller` listener 시그니처 확장 — entry id 전달

**Files:**

- Modify: `apps/api/src/main/kotlin/com/bara/api/application/port/out/TaskEventBusPort.kt` (listener 타입 변경)
- Modify: `apps/api/src/main/kotlin/com/bara/api/adapter/out/redis/EventBusPoller.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/adapter/out/redis/RedisStreamTaskEventBus.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/application/service/command/StreamMessageService.kt` (listener 업데이트)
- Modify: `apps/api/src/main/kotlin/com/bara/api/application/service/query/SubscribeTaskService.kt` (listener 업데이트)
- Modify: `apps/api/src/test/...` (깨진 테스트들 파라미터 업데이트)

**이유:** 현재 `subscribe(taskId, fromId, listener: (TaskEvent) -> Unit)` 시그니처는 리스너가 Redis Stream entry id 를 알 수 없다. SSE 프레임 `id:` 는 반드시 entry id 여야 `Last-Event-ID` 재연결이 성립한다.

- [ ] **Step 1: 깨질 테스트 먼저 업데이트 (TDD 방식: 원하는 시그니처를 테스트에 먼저 반영)**

`StreamMessageServiceTest.kt` 와 `SubscribeTaskServiceTest.kt` 의 `bus.subscribe(...)` 스텁을 새 시그니처 `(entryId: String, event: TaskEvent) -> Unit` 로 맞춘다. `SseBridgeTest.kt` 의 listener 타입도 업데이트.

```kotlin
// 예: 기존
every { bus.subscribe(any(), "0", any()) } returns subscription
// 변경 필요 없음 (capture slot 타입만 바꿈)
val listenerSlot = slot<(String, TaskEvent) -> Unit>()
every { bus.subscribe(any(), "0", capture(listenerSlot)) } returns subscription
```

- [ ] **Step 2: 테스트 실행 → 컴파일 실패**

```bash
./gradlew :apps:api:test
```

Expected: 타입 mismatch on subscribe signature.

- [ ] **Step 3: `TaskEventBusPort.subscribe` 시그니처 변경**

```kotlin
fun subscribe(
    taskId: String,
    fromStreamId: String,
    listener: (entryId: String, event: TaskEvent) -> Unit,
): Subscription
```

`await` 내부에서도 `subscribe(..., { _, event -> ... })` 로 업데이트.

- [ ] **Step 4: `EventBusPoller` listener 타입 변경 + 호출 지점 업데이트**

```kotlin
class EventBusPoller(
    ...
    private val listener: (entryId: String, event: TaskEvent) -> Unit,
    ...
) : Subscription {
    ...
    private fun pollOnce() {
        ...
        for (record in records) {
            val json = record.value["event"]?.toString() ?: continue
            val event = TaskEventJson.deserialize(objectMapper, json)
            val id = record.id.toString()
            try { listener(id, event) } catch (...) {}
            lastId.set(id)
        }
        ...
    }
}
```

- [ ] **Step 5: `RedisStreamTaskEventBus` 의 `await` 내부 listener 업데이트**

```kotlin
val subscription = subscribe(taskId, "0") { _, event ->
    if (event.final && !future.isDone) future.complete(event)
}
```

- [ ] **Step 6: `StreamMessageService` / `SubscribeTaskService` listener 업데이트**

```kotlin
// StreamMessageService
val subscription = taskEventBusPort.subscribe(taskId, "0") { entryId, event ->
    val dto = A2ATaskMapper.fromEvent(task, event)
    sseBridge.send(taskId, entryId, dto, event.final)
}

// SubscribeTaskService
val subscription = taskEventBusPort.subscribe(taskId, fromId) { entryId, event ->
    val dto = A2ATaskMapper.fromEvent(task, event)
    sseBridge.send(taskId, entryId, dto, event.final)
}
```

그리고 `StreamMessageService` 의 `entryIdRef` AtomicReference 보일러플레이트 제거.

- [ ] **Step 7: 전체 테스트 실행**

```bash
./gradlew :apps:api:test
```

Expected: BUILD SUCCESSFUL. 기존 `RedisStreamTaskEventBusTest` / `EventBusPollerTest` 등이 있으면 함께 업데이트되어야 한다 — 있으면 listener 타입을 `(entryId, event) -> Unit` 으로 맞춰주고, 호출 검증도 `(entryId, event)` 둘 다 받도록 변경.

- [ ] **Step 8: Commit**

```bash
git add apps/api/src/main/kotlin/com/bara/api/application/port/out/TaskEventBusPort.kt \
        apps/api/src/main/kotlin/com/bara/api/adapter/out/redis/EventBusPoller.kt \
        apps/api/src/main/kotlin/com/bara/api/adapter/out/redis/RedisStreamTaskEventBus.kt \
        apps/api/src/main/kotlin/com/bara/api/application/service/command/StreamMessageService.kt \
        apps/api/src/main/kotlin/com/bara/api/application/service/query/SubscribeTaskService.kt \
        apps/api/src/test/kotlin/com/bara/api
git commit -m "refactor(api): propagate Redis stream entry id to subscriber listeners for SSE id mapping"
```

---

## Task 10: e2e 인프라 — `SseTestClient` 와 build.gradle 업데이트

**Files:**

- Modify: `apps/api/build.gradle.kts` (e2eTest 에 okhttp-sse 추가, 없는 경우 okhttp 도)
- Create: `apps/api/src/e2eTest/kotlin/com/bara/api/e2e/support/SseTestClient.kt`

- [ ] **Step 1: `build.gradle.kts` 읽어서 기존 의존성 확인**

Read `apps/api/build.gradle.kts`. `e2eTestImplementation` block 에 `com.squareup.okhttp3:okhttp` 가 있으면 `okhttp-sse` 만 추가. 없으면 둘 다 추가.

- [ ] **Step 2: `build.gradle.kts` 수정**

예시 추가:

```kotlin
e2eTestImplementation("com.squareup.okhttp3:okhttp:4.12.0")
e2eTestImplementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
```

(기존과 버전 충돌 시 스프링 BOM 관리 버전 사용 — e.g. `org.springframework.boot:spring-boot-dependencies` 가 관리하는 okhttp 버전 따라감.)

- [ ] **Step 3: `SseTestClient` 작성**

새 파일 `apps/api/src/e2eTest/kotlin/com/bara/api/e2e/support/SseTestClient.kt`:

```kotlin
package com.bara.api.e2e.support

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * OkHttp 기반 SSE 테스트 클라이언트.
 *
 * 수신한 이벤트를 BlockingQueue 로 보관하고 `nextEvent(timeout)` 으로 polling.
 * `closeFuture` 는 SSE 종료(서버 complete 또는 에러) 완료 시점.
 */
class SseTestClient(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
) : AutoCloseable {

    data class Received(val id: String?, val event: String?, val data: String)

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val queue = LinkedBlockingQueue<Received>()
    private val closeFuture = CompletableFuture<Throwable?>()
    private var source: EventSource? = null

    fun open() {
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (k, v) -> requestBuilder.header(k, v) }
        source = EventSources.createFactory(client).newEventSource(
            requestBuilder.build(),
            object : EventSourceListener() {
                override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                    queue.offer(Received(id, type, data))
                }
                override fun onClosed(es: EventSource) {
                    closeFuture.complete(null)
                }
                override fun onFailure(es: EventSource, t: Throwable?, response: okhttp3.Response?) {
                    closeFuture.complete(t ?: RuntimeException("SSE failure"))
                }
            }
        )
    }

    fun nextEvent(timeoutMs: Long = 10_000): Received? =
        queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    fun waitForClose(timeoutMs: Long = 30_000): Throwable? =
        closeFuture.get(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() {
        source?.cancel()
        client.dispatcher.executorService.shutdownNow()
        client.connectionPool.evictAll()
    }
}
```

- [ ] **Step 4: 컴파일 확인**

```bash
./gradlew :apps:api:compileE2eTestKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add apps/api/build.gradle.kts apps/api/src/e2eTest/kotlin/com/bara/api/e2e/support/SseTestClient.kt
git commit -m "test(api): add OkHttp SSE test client for e2e streaming scenarios"
```

---

## Task 11: e2e 시나리오 테스트 — 3 개

**Files:**

- Create: `apps/api/src/e2eTest/kotlin/com/bara/api/e2e/scenario/TaskStreamingScenarioTest.kt`

**시나리오:**

1. `POST /agents/{name}/message:stream` 이 submitted → completed 이벤트 순서대로 방출. 마지막 프레임이 `final:true`. `id:` 가 Redis Stream entry id 포맷 (`ms-seq`).
2. `GET /agents/{name}/tasks/{taskId}:subscribe` 이 `Last-Event-ID=<첫 프레임 id>` 로 재연결해 이후 이벤트만 수신.
3. 터미널 후 grace period 경과시 `:subscribe` 가 410 + `-32067`.

- [ ] **Step 1: 시나리오 1 작성 (스트림 완료)**

기존 `TaskProcessingScenarioTest` 의 setup (FakeAgent, RestTemplate, Testcontainers) 을 참고해서 작성. 파일 `apps/api/src/e2eTest/kotlin/com/bara/api/e2e/scenario/TaskStreamingScenarioTest.kt`:

```kotlin
package com.bara.api.e2e.scenario

import com.bara.api.e2e.support.SseTestClient
import com.bara.api.e2e.support.E2EBase   // Phase 2 테스트와 같은 베이스
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Duration
import java.util.concurrent.TimeUnit

@Timeout(value = 60, unit = TimeUnit.SECONDS)
class TaskStreamingScenarioTest : E2EBase() {

    private val objectMapper = ObjectMapper()

    @Test
    fun `message stream emits submitted then working then completed with final true`() {
        val agentName = registerAndActivateAgent()  // 기존 헬퍼 재사용
        val url = "$baseUrl/agents/$agentName/message:stream"
        val body = """
            {"jsonrpc":"2.0","id":"req-1","method":"message/stream",
             "params":{"message":{"messageId":"m1","parts":[{"text":"hi"}]}}}
        """.trimIndent()

        startFakeAgentReplyAfter(Duration.ofMillis(500))

        SseTestClient(
            url = url,
            headers = mapOf(
                "X-User-Id" to "user-1",
                "Accept" to "text/event-stream",
                "Content-Type" to "application/json",
            ),
        ).use { client ->
            // message:stream 은 POST 라 OkHttp EventSources 는 GET 전용이다.
            // 대안: OkHttp Request 에 postBody 를 지정하려면 RequestBody 세팅 + method("POST", body).
            // SseTestClient 를 확장하거나 별도 open(method, body) 를 추가해야 한다.
            // → 여기서는 open() 오버로드를 사용한다 (Task 10 에 POST 지원 추가 필요).
            client.openPost(body, "application/json")

            val first = client.nextEvent(15_000)
            assertThat(first).isNotNull
            assertThat(first!!.event).isEqualTo("message")
            val firstDto = objectMapper.readTree(first.data)
            assertThat(firstDto.path("result").path("status").path("state").asText())
                .isEqualTo("submitted")
            assertThat(first.id).matches("""\d+-\d+""")

            // 후속 이벤트: working (optional) → completed final=true
            var last = first
            var terminalSeen = false
            while (!terminalSeen) {
                val evt = client.nextEvent(15_000) ?: break
                last = evt
                val state = objectMapper.readTree(evt.data).path("result").path("status").path("state").asText()
                if (state == "completed" || state == "failed") terminalSeen = true
            }
            assertThat(terminalSeen).isTrue
        }
    }

    @Test
    fun `tasks subscribe with Last-Event-ID replays only later events`() {
        // 1. stream 열고 첫 이벤트 받은 후 disconnect
        // 2. subscribe Last-Event-ID=첫이벤트.id 로 재연결
        // 3. 수신되는 첫 이벤트가 submitted 가 아닌 working 또는 completed 여야 함
        // (전체 검증은 구현 참고)
    }

    @Test
    fun `tasks subscribe returns 410 when stream has expired`() {
        // 1. stream 으로 태스크 하나 끝까지 완료
        // 2. streamGracePeriodSeconds 를 테스트 프로퍼티로 1 초로 override
        // 3. 2초 대기
        // 4. :subscribe 요청 → 410 + -32067 검증
    }
}
```

**중요:** `SseTestClient.openPost()` 지원을 Task 10 에 추가해야 할 수도 있다. OkHttp `EventSources` 는 기본적으로 GET 만 지원하므로 POST 를 명시적으로 builder 에 넣어야 한다. 필요 시 Task 10 을 먼저 확장.

- [ ] **Step 2: `SseTestClient` 에 POST 지원 추가 (필요 시)**

```kotlin
fun openPost(body: String, contentType: String = "application/json") {
    val reqBody = okhttp3.RequestBody.create(
        okhttp3.MediaType.parse(contentType),
        body,
    )
    val builder = okhttp3.Request.Builder().url(url).post(reqBody)
    headers.forEach { (k, v) -> builder.header(k, v) }
    source = EventSources.createFactory(client).newEventSource(builder.build(), listener)
}
```

(리스너는 `open()` 과 공유되도록 별도 프로퍼티로 분리.)

- [ ] **Step 3: 시나리오 2, 3 구현**

주석으로만 표기된 두 시나리오를 실제 코드로 채움.

- 시나리오 2: `submitted` 이벤트 id 를 기록 → client.close → `SseTestClient("$baseUrl/agents/$agentName/tasks/$taskId:subscribe", headers + ("Last-Event-ID" to firstId))` 재연결 → 첫 수신 이벤트의 state 가 `working` 또는 `completed` 인지 검증.
- 시나리오 3: `application-e2e.yml` 에 `bara.api.task.stream-grace-period-seconds=1` 설정으로 override. 태스크 완료 후 `Thread.sleep(2500)`. subscribe 요청 → HTTP 410 + body.error.code == -32067.

- [ ] **Step 4: 전체 e2e 테스트 실행**

```bash
./gradlew :apps:api:e2eTest --tests "com.bara.api.e2e.scenario.TaskStreamingScenarioTest"
```

Expected: BUILD SUCCESSFUL, 3 tests passed.

- [ ] **Step 5: 기존 e2e 회귀 확인**

```bash
./gradlew :apps:api:e2eTest
```

Expected: 기존 Phase 1/2 9개 + Phase 3 3개 모두 통과.

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/e2eTest/kotlin/com/bara/api/e2e/support/SseTestClient.kt \
        apps/api/src/e2eTest/kotlin/com/bara/api/e2e/scenario/TaskStreamingScenarioTest.kt
git commit -m "test(api): add e2e scenarios for SSE stream, subscribe reconnect, and expired stream"
```

---

## Task 12: 로깅 문서 업데이트

**Files:**

- Modify: `docs/guides/logging/flows/api-task-processing.md`

- [ ] **Step 1: 기존 문서 읽기**

```bash
cat docs/guides/logging/flows/api-task-processing.md
```

- [ ] **Step 2: 다음 섹션을 추가/갱신**

```markdown
### POST /agents/{agentName}/message:stream

필수 필드:

- `task_id` (String)
- `agent_id`, `agent_name` (String)
- `user_id` (String)
- `request_id` (String)
- `outcome` (String): `stream_started` | `stream_completed` | `stream_client_disconnect`
  | `stream_server_timeout` | `agent_unavailable` | `kafka_publish_failed`

선택 필드:

- `sse_event_count` (Int) — 전송된 SSE 이벤트 개수 (연결 종료 시점)
- `stream_duration_ms` (Long)
- `disconnect_reason` (String) — `final`, `client`, `timeout`, `error`

### GET /agents/{agentName}/tasks/{taskId}:subscribe

필수 필드:

- `task_id`, `user_id`
- `last_event_id` (String or `"null"`)
- `outcome`: `stream_resubscribed` | `stream_expired` | `task_not_found` | `task_access_denied`

선택 필드:

- `backfill_count` (Int) — backfill 로 리플레이된 이벤트 수
```

- [ ] **Step 3: Commit**

```bash
git add docs/guides/logging/flows/api-task-processing.md
git commit -m "docs(api): add Phase 3 SSE logging fields for message:stream and :subscribe"
```

---

## Task 13: 최종 검증 — 로컬 실행 + PR 준비

**Files:** 없음 (운영 검증)

- [ ] **Step 1: Docker 이미지 빌드 + k3d 로컬 배포**

```bash
./scripts/docker.sh build api
./scripts/k8s.sh load
```

- [ ] **Step 2: `curl` 로 수동 검증 — SSE stream**

```bash
# 사전: Auth 로그인 + JWT 확보 후 X-User-Id 추출 가능한 상태
# 또는 dev 모드로 Traefik forwardAuth 우회하도록 환경 설정

curl -N -X POST http://localhost/api/core/agents/default-agent/message:stream \
  -H 'X-User-Id: test-user' \
  -H 'Accept: text/event-stream' \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":"r1","method":"message/stream","params":{"message":{"messageId":"m1","parts":[{"text":"hello"}]}}}'
```

Expected: `id: ..., event: message, data: {...submitted...}` → `data: {...completed...}` 순서로 프레임 수신, 마지막 프레임이 `final:true`.

- [ ] **Step 2: `curl` 로 수동 검증 — :subscribe 재연결**

```bash
TASK_ID=$(curl -sN ... | 첫 프레임의 result.id 추출)
FIRST_ID=... # 첫 프레임의 id:

curl -N http://localhost/api/core/agents/default-agent/tasks/$TASK_ID:subscribe \
  -H 'X-User-Id: test-user' \
  -H "Last-Event-ID: $FIRST_ID" \
  -H 'Accept: text/event-stream'
```

Expected: 첫 프레임이 `submitted` 가 아닌 후속 상태(backfill).

- [ ] **Step 3: `:subscribe` 410 수동 검증**

그레이스 기간(60초) 경과 후 동일 taskId 로 `:subscribe` 요청 → HTTP 410 + `{"error":{"code":-32067,...}}`.

- [ ] **Step 4: 전체 빌드 + 테스트 재확인**

```bash
./gradlew :apps:api:build
./gradlew :apps:api:e2eTest
```

Expected: ALL GREEN.

- [ ] **Step 5: PR 작성 (main 아닌 develop 대상)**

```bash
git push -u origin feat/api/task-processing-phase3
gh pr create --base develop --title "feat(api): Phase 3 SSE streaming and subscribe reconnect" --body "$(cat <<'EOF'
## Summary
- `POST /agents/{name}/message:stream` SSE 스트리밍 엔드포인트 추가 (시나리오 C)
- `GET /agents/{name}/tasks/{taskId}:subscribe` SSE 재연결 엔드포인트 추가 (시나리오 D)
- `StreamUnsupportedException` (-32067 → 410 GONE) 으로 만료된 스트림 재연결 거부
- `SseBridge` + `SseHeartbeatScheduler` 로 idle SSE 연결 유지 (15초 keepalive)
- `TaskEventBusPort.subscribe` listener 시그니처가 Redis Stream entry id 를 전달해 SSE `id:` 필드 정확 매핑

## Test plan
- [ ] 기존 Phase 1/2 테스트 회귀 없음
- [ ] 신규 unit/web-slice 테스트 전부 통과
- [ ] e2e 3 시나리오 (stream 완료 / subscribe 재연결 / 410 만료) 통과
- [ ] 수동 curl 검증 (stream + subscribe + 410)
EOF
)"
```

---

## 에러 처리 최종 테이블 (Phase 3 기준)

| 케이스                      | 포착                              | HTTP          | JSON-RPC code | outcome                    |
| --------------------------- | --------------------------------- | ------------- | ------------- | -------------------------- |
| agent 미등록 (stream)       | `AgentUnavailableException`       | 503           | -32062        | `agent_unavailable`        |
| kafka publish 실패 (stream) | `KafkaPublishException`           | 502           | -32001        | `kafka_publish_failed`     |
| task 없음 (:subscribe)      | `TaskNotFoundException`           | 404           | -32064        | `task_not_found`           |
| 다른 유저 (:subscribe)      | `TaskAccessDeniedException`       | 403           | -32065        | `task_access_denied`       |
| 스트림 만료 (:subscribe)    | `StreamUnsupportedException`      | 410           | -32067        | `stream_expired`           |
| emitter timeout (30분)      | `SseEmitter.onTimeout`            | — (SSE close) | —             | `stream_server_timeout`    |
| client disconnect           | `SseEmitter.onCompletion/onError` | —             | —             | `stream_client_disconnect` |

---

## Self-Review 체크리스트 (플랜 작성자용)

- [x] 스펙 §2.7 의 `-32067 STREAM_UNSUPPORTED` → Task 1
- [x] 스펙 §3 시나리오 C (message:stream) → Task 5~6, 8
- [x] 스펙 §3 시나리오 D (:subscribe) → Task 7, 8
- [x] §4.6 `emitter-timeout 30분`, `하트비트 15초` → Task 2, 3, 4
- [x] §3 "submitted 도 XADD" 는 `StreamMessageService` 가 `EventBus.publish(submitted)` 유지로 충족 (Task 6)
- [x] Last-Event-ID ↔ Redis Stream entry id 매핑 → Task 9
- [x] `ResultConsumerAdapter` 의 `eventBus.close()` 로직은 그대로, Phase 3 서비스는 중복 호출 안 함 (Task 6 구현 주석)
- [x] 테스트 계층: unit (SSE bridge, 두 서비스) + web-slice (@WebMvcTest) + e2e (Testcontainers + OkHttp SSE) 전부 포함
- [x] 로깅 필드 문서 업데이트 → Task 12
- [x] 수동 curl 검증 → Task 13

## 범위 외 (명시)

- cancel (`tasks/{id}:cancel`) — 다음 이터레이션
- multi-turn conversation 이력 조회
- Micrometer metric
- pushNotificationConfig
- Redis Stream de-dup
- FE 채팅 UI
- OAuth 2.1 user scope 변경

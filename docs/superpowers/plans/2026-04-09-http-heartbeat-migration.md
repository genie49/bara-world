# HTTP Heartbeat Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Kafka-based heartbeat with HTTP-based heartbeat, unifying registration and heartbeat on a single transport.

**Architecture:** Agent calls `POST /agents/{name}/registry` on startup (registration), then `POST /agents/{name}/heartbeat` every 20s (liveness). API Service's existing HeartbeatConsumer (Kafka) is replaced by a new HeartbeatAgentService (HTTP handler). Agent gets a new RegistryClient (httpx) replacing HeartbeatLoop (aiokafka).

**Tech Stack:** Kotlin + Spring Boot (API Service), Python + FastAPI + httpx (Agent), Redis (registry TTL)

---

## File Structure

### API Service (Kotlin)

| Action | File                                                                    | Responsibility                                                 |
| ------ | ----------------------------------------------------------------------- | -------------------------------------------------------------- |
| Create | `apps/api/.../application/port/in/command/HeartbeatAgentUseCase.kt`     | Use case interface                                             |
| Create | `apps/api/.../application/service/command/HeartbeatAgentService.kt`     | Heartbeat logic: verify registration + ownership + refresh TTL |
| Create | `apps/api/.../domain/exception/AgentNotRegisteredException.kt`          | Agent not in Redis registry                                    |
| Modify | `apps/api/.../adapter/in/rest/AgentController.kt`                       | Add `POST /{agentName}/heartbeat` endpoint                     |
| Delete | `apps/api/.../adapter/in/kafka/HeartbeatConsumer.kt`                    | Kafka heartbeat consumer                                       |
| Delete | `apps/api/.../adapter/in/kafka/HeartbeatConsumerTest.kt`                | Consumer tests                                                 |
| Modify | `apps/api/.../adapter/in/rest/ApiExceptionHandler.kt`                   | Add handler for AgentNotRegisteredException                    |
| Modify | `apps/api/.../adapter/in/rest/AgentControllerTest.kt`                   | Add heartbeat endpoint tests                                   |
| Create | `apps/api/.../application/service/command/HeartbeatAgentServiceTest.kt` | Service unit tests                                             |

### Agent (Python)

| Action | File                                     | Responsibility                                                        |
| ------ | ---------------------------------------- | --------------------------------------------------------------------- |
| Create | `agents/default/app/registry.py`         | RegistryClient: register + heartbeat loop via httpx                   |
| Modify | `agents/default/app/config.py`           | Add agent_name, api_service_url, provider_api_key, heartbeat_interval |
| Modify | `agents/default/app/main.py`             | Replace HeartbeatLoop with RegistryClient                             |
| Modify | `agents/default/app/models/messages.py`  | Remove HeartbeatMessage                                               |
| Modify | `agents/default/app/kafka/producer.py`   | Remove send_heartbeat                                                 |
| Delete | `agents/default/app/kafka/heartbeat.py`  | Kafka HeartbeatLoop                                                   |
| Delete | `agents/default/tests/test_heartbeat.py` | HeartbeatLoop tests                                                   |
| Modify | `agents/default/tests/test_messages.py`  | Remove HeartbeatMessage test                                          |
| Modify | `agents/default/tests/test_producer.py`  | Remove send_heartbeat test                                            |
| Modify | `agents/default/tests/test_health.py`    | Patch RegistryClient instead of HeartbeatLoop                         |
| Create | `agents/default/tests/test_registry.py`  | RegistryClient tests                                                  |
| Modify | `agents/default/pyproject.toml`          | Add httpx dependency                                                  |
| Modify | `agents/default/.env.example`            | Add new config fields                                                 |

### Infrastructure & Docs

| Action | File                                 | Responsibility                       |
| ------ | ------------------------------------ | ------------------------------------ |
| Modify | `infra/k8s/base/gateway/routes.yaml` | Add heartbeat route with forwardAuth |
| Modify | `docs/spec/api/agent-registry.md`    | Update heartbeat section             |
| Modify | `CLAUDE.md`                          | Update architecture description      |

---

## Task 1: HeartbeatAgentUseCase + HeartbeatAgentService (API Service)

**Files:**

- Create: `apps/api/src/main/kotlin/com/bara/api/application/port/in/command/HeartbeatAgentUseCase.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/application/service/command/HeartbeatAgentService.kt`
- Create: `apps/api/src/main/kotlin/com/bara/api/domain/exception/AgentNotRegisteredException.kt`
- Create: `apps/api/src/test/kotlin/com/bara/api/application/service/command/HeartbeatAgentServiceTest.kt`

- [ ] **Step 1: Create AgentNotRegisteredException**

```kotlin
package com.bara.api.domain.exception

class AgentNotRegisteredException(agentName: String) :
    RuntimeException("Agent is not registered: $agentName")
```

Write to `apps/api/src/main/kotlin/com/bara/api/domain/exception/AgentNotRegisteredException.kt`.

- [ ] **Step 2: Create HeartbeatAgentUseCase interface**

```kotlin
package com.bara.api.application.port.`in`.command

interface HeartbeatAgentUseCase {
    fun heartbeat(providerId: String, agentName: String)
}
```

Write to `apps/api/src/main/kotlin/com/bara/api/application/port/in/command/HeartbeatAgentUseCase.kt`.

- [ ] **Step 3: Write failing test for HeartbeatAgentService**

```kotlin
package com.bara.api.application.service.command

import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNotRegisteredException
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.api.domain.exception.AgentOwnershipException
import com.bara.api.domain.model.Agent
import com.bara.api.domain.model.AgentCard
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class HeartbeatAgentServiceTest {

    private val agentRepository = mockk<AgentRepository>()
    private val agentRegistryPort = mockk<AgentRegistryPort>()
    private val service = HeartbeatAgentService(agentRepository, agentRegistryPort)

    private val agentCard = AgentCard(name = "Test", description = "test", version = "1.0.0")
    private val agent = Agent(
        id = "a-1", name = "my-agent", providerId = "p-1",
        agentCard = agentCard, createdAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun `등록된 Agent의 heartbeat는 TTL을 갱신한다`() {
        every { agentRegistryPort.isRegistered("my-agent") } returns true
        every { agentRepository.findByName("my-agent") } returns agent
        justRun { agentRegistryPort.refreshTtl("my-agent") }

        service.heartbeat("p-1", "my-agent")

        verify { agentRegistryPort.refreshTtl("my-agent") }
    }

    @Test
    fun `미등록 Agent의 heartbeat는 AgentNotRegisteredException 발생`() {
        every { agentRegistryPort.isRegistered("unknown") } returns false

        assertThrows<AgentNotRegisteredException> {
            service.heartbeat("p-1", "unknown")
        }
    }

    @Test
    fun `존재하지 않는 Agent는 AgentNotFoundException 발생`() {
        every { agentRegistryPort.isRegistered("ghost") } returns true
        every { agentRepository.findByName("ghost") } returns null

        assertThrows<AgentNotFoundException> {
            service.heartbeat("p-1", "ghost")
        }
    }

    @Test
    fun `소유권 불일치 시 AgentOwnershipException 발생`() {
        every { agentRegistryPort.isRegistered("my-agent") } returns true
        every { agentRepository.findByName("my-agent") } returns agent

        assertThrows<AgentOwnershipException> {
            service.heartbeat("p-other", "my-agent")
        }
    }
}
```

Write to `apps/api/src/test/kotlin/com/bara/api/application/service/command/HeartbeatAgentServiceTest.kt`.

- [ ] **Step 4: Run test to verify it fails**

Run: `./gradlew :apps:api:test --tests "com.bara.api.application.service.command.HeartbeatAgentServiceTest"`
Expected: FAIL — `HeartbeatAgentService` does not exist yet.

- [ ] **Step 5: Implement HeartbeatAgentService**

```kotlin
package com.bara.api.application.service.command

import com.bara.api.application.port.`in`.command.HeartbeatAgentUseCase
import com.bara.api.application.port.out.AgentRegistryPort
import com.bara.api.application.port.out.AgentRepository
import com.bara.api.domain.exception.AgentNotRegisteredException
import com.bara.api.domain.exception.AgentNotFoundException
import com.bara.api.domain.exception.AgentOwnershipException
import com.bara.common.logging.WideEvent
import org.springframework.stereotype.Service

@Service
class HeartbeatAgentService(
    private val agentRepository: AgentRepository,
    private val agentRegistryPort: AgentRegistryPort,
) : HeartbeatAgentUseCase {

    override fun heartbeat(providerId: String, agentName: String) {
        if (!agentRegistryPort.isRegistered(agentName)) {
            throw AgentNotRegisteredException(agentName)
        }

        val agent = agentRepository.findByName(agentName)
            ?: throw AgentNotFoundException()

        if (agent.providerId != providerId) {
            throw AgentOwnershipException()
        }

        agentRegistryPort.refreshTtl(agentName)

        WideEvent.put("agent_id", agent.id)
        WideEvent.put("agent_name", agentName)
        WideEvent.put("provider_id", providerId)
        WideEvent.put("outcome", "heartbeat_refreshed")
        WideEvent.message("Heartbeat TTL 갱신")
    }
}
```

Write to `apps/api/src/main/kotlin/com/bara/api/application/service/command/HeartbeatAgentService.kt`.

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :apps:api:test --tests "com.bara.api.application.service.command.HeartbeatAgentServiceTest"`
Expected: PASS (all 4 tests).

- [ ] **Step 7: Commit**

```bash
git add apps/api/src/main/kotlin/com/bara/api/domain/exception/AgentNotRegisteredException.kt apps/api/src/main/kotlin/com/bara/api/application/port/in/command/HeartbeatAgentUseCase.kt apps/api/src/main/kotlin/com/bara/api/application/service/command/HeartbeatAgentService.kt apps/api/src/test/kotlin/com/bara/api/application/service/command/HeartbeatAgentServiceTest.kt
git commit -m "feat(api): add HeartbeatAgentService for HTTP-based heartbeat"
```

---

## Task 2: Controller + ExceptionHandler + Delete HeartbeatConsumer (API Service)

**Files:**

- Modify: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/AgentController.kt`
- Modify: `apps/api/src/main/kotlin/com/bara/api/adapter/in/rest/ApiExceptionHandler.kt`
- Modify: `apps/api/src/test/kotlin/com/bara/api/adapter/in/rest/AgentControllerTest.kt`
- Delete: `apps/api/src/main/kotlin/com/bara/api/adapter/in/kafka/HeartbeatConsumer.kt`
- Delete: `apps/api/src/test/kotlin/com/bara/api/adapter/in/kafka/HeartbeatConsumerTest.kt`

- [ ] **Step 1: Add heartbeat endpoint to AgentController**

In `AgentController.kt`, add `HeartbeatAgentUseCase` to the constructor and add the `POST` endpoint. The new constructor parameter goes after `registryAgentUseCase`:

Replace the constructor:

```kotlin
class AgentController(
    private val registerAgentUseCase: RegisterAgentUseCase,
    private val deleteAgentUseCase: DeleteAgentUseCase,
    private val registryAgentUseCase: RegistryAgentUseCase,
    private val heartbeatAgentUseCase: HeartbeatAgentUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    private val listAgentsQuery: ListAgentsQuery,
    private val getAgentQuery: GetAgentQuery,
    private val getAgentCardQuery: GetAgentCardQuery,
)
```

Add import: `import com.bara.api.application.port.\`in\`.command.HeartbeatAgentUseCase`

Add endpoint after the `registry` method (after line 60):

```kotlin
    @PostMapping("/{agentName}/heartbeat")
    fun heartbeat(
        @RequestHeader("X-Provider-Id") providerId: String,
        @PathVariable agentName: String,
    ): ResponseEntity<Void> {
        heartbeatAgentUseCase.heartbeat(providerId, agentName)
        return ResponseEntity.ok().build()
    }
```

- [ ] **Step 2: Add AgentNotRegisteredException handler to ApiExceptionHandler**

In `ApiExceptionHandler.kt`, add import and handler:

```kotlin
import com.bara.api.domain.exception.AgentNotRegisteredException
```

Add handler method after the `handleAgentOwnership` method:

```kotlin
    @ExceptionHandler(AgentNotRegisteredException::class)
    fun handleAgentNotRegistered(ex: AgentNotRegisteredException): ResponseEntity<ErrorResponse> {
        WideEvent.put("error_type", "AgentNotRegisteredException")
        WideEvent.put("outcome", "agent_not_registered")
        WideEvent.message("Agent 미등록 상태")
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse("agent_not_registered", ex.message ?: "Agent is not registered"))
    }
```

- [ ] **Step 3: Add heartbeat controller tests**

In `AgentControllerTest.kt`, add the `HeartbeatAgentUseCase` mock bean (after `registryAgentUseCase`):

```kotlin
    @MockkBean
    lateinit var heartbeatAgentUseCase: HeartbeatAgentUseCase
```

Add import: `import com.bara.api.application.port.\`in\`.command.HeartbeatAgentUseCase`

Add these test methods at the end of the class (before the closing `}`):

```kotlin
    @Test
    fun `POST agents heartbeat 성공 시 200`() {
        justRun { heartbeatAgentUseCase.heartbeat("p-1", "my-agent") }

        mockMvc.post("/agents/my-agent/heartbeat") {
            header("X-Provider-Id", "p-1")
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    fun `POST agents heartbeat 미등록 Agent 시 404`() {
        every { heartbeatAgentUseCase.heartbeat("p-1", "unknown") } throws AgentNotRegisteredException("unknown")

        mockMvc.post("/agents/unknown/heartbeat") {
            header("X-Provider-Id", "p-1")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.error") { value("agent_not_registered") }
        }
    }

    @Test
    fun `POST agents heartbeat 소유권 불일치 시 403`() {
        every { heartbeatAgentUseCase.heartbeat("p-1", "other-agent") } throws AgentOwnershipException()

        mockMvc.post("/agents/other-agent/heartbeat") {
            header("X-Provider-Id", "p-1")
        }.andExpect {
            status { isForbidden() }
        }
    }
```

Add import: `import com.bara.api.domain.exception.AgentNotRegisteredException`

- [ ] **Step 4: Delete HeartbeatConsumer and its test**

Delete files:

- `apps/api/src/main/kotlin/com/bara/api/adapter/in/kafka/HeartbeatConsumer.kt`
- `apps/api/src/test/kotlin/com/bara/api/adapter/in/kafka/HeartbeatConsumerTest.kt`

Check if `adapter/in/kafka/` directory has any other files. If empty after deletion, remove the directory.

- [ ] **Step 5: Run all API tests**

Run: `./gradlew :apps:api:test`
Expected: All tests PASS. This includes the new heartbeat controller tests and service tests, and the HeartbeatConsumer tests are gone.

- [ ] **Step 6: Commit**

```bash
git add -A apps/api/
git commit -m "feat(api): add POST heartbeat endpoint and remove Kafka HeartbeatConsumer"
```

---

## Task 3: Agent — Settings + RegistryClient + main.py

**Files:**

- Modify: `agents/default/app/config.py`
- Create: `agents/default/app/registry.py`
- Modify: `agents/default/app/main.py`
- Modify: `agents/default/pyproject.toml`
- Modify: `agents/default/.env.example`

- [ ] **Step 1: Add httpx dependency**

In `agents/default/pyproject.toml`, add `"httpx>=0.27.0"` to the `dependencies` list (after `aiokafka`):

```toml
    "aiokafka>=0.12.0",
    "httpx>=0.27.0",
```

Then run: `cd agents/default && uv lock && uv sync`

- [ ] **Step 2: Update Settings**

Replace entire `agents/default/app/config.py`:

```python
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    google_api_key: str = ""
    agent_id: str = "default-agent"
    agent_name: str = "default-agent"
    kafka_bootstrap_servers: str = "localhost:30092"
    api_service_url: str = "http://localhost/api/core"
    provider_api_key: str = ""
    heartbeat_interval: int = 20
    port: int = 8090

    model_config = {"env_file": ".env", "env_file_encoding": "utf-8"}
```

- [ ] **Step 3: Update .env.example**

Replace entire `agents/default/.env.example`:

```env
GOOGLE_API_KEY=your-gemini-api-key-here
AGENT_ID=default-agent
AGENT_NAME=default-agent
KAFKA_BOOTSTRAP_SERVERS=localhost:30092
API_SERVICE_URL=http://localhost/api/core
PROVIDER_API_KEY=your-api-key
HEARTBEAT_INTERVAL=20
PORT=8090
```

- [ ] **Step 4: Write failing tests for RegistryClient**

```python
import asyncio
from unittest.mock import AsyncMock

import httpx
import pytest

from app.config import Settings
from app.registry import RegistryClient


@pytest.fixture
def settings():
    return Settings(
        agent_name="my-agent",
        api_service_url="http://test",
        provider_api_key="test-key",
        heartbeat_interval=0.1,
    )


@pytest.mark.asyncio
async def test_register_success(settings):
    transport = httpx.MockTransport(lambda req: httpx.Response(200))
    client = RegistryClient(settings, transport=transport)
    await client.register()
    await client.close()


@pytest.mark.asyncio
async def test_register_failure_raises(settings):
    transport = httpx.MockTransport(lambda req: httpx.Response(404))
    client = RegistryClient(settings, transport=transport)
    with pytest.raises(Exception):
        await client.register()
    await client.close()


@pytest.mark.asyncio
async def test_heartbeat_loop_sends_requests(settings):
    call_count = 0

    def handler(req: httpx.Request) -> httpx.Response:
        nonlocal call_count
        call_count += 1
        return httpx.Response(200)

    transport = httpx.MockTransport(handler)
    client = RegistryClient(settings, transport=transport)
    task = asyncio.create_task(client.heartbeat_loop())
    await asyncio.sleep(0.35)
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass
    await client.close()
    assert call_count >= 2


@pytest.mark.asyncio
async def test_heartbeat_failure_does_not_raise(settings):
    transport = httpx.MockTransport(lambda req: httpx.Response(500))
    client = RegistryClient(settings, transport=transport)
    task = asyncio.create_task(client.heartbeat_loop())
    await asyncio.sleep(0.25)
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass
    await client.close()


@pytest.mark.asyncio
async def test_close_cleans_up(settings):
    transport = httpx.MockTransport(lambda req: httpx.Response(200))
    client = RegistryClient(settings, transport=transport)
    await client.close()
```

Write to `agents/default/tests/test_registry.py`.

- [ ] **Step 5: Run test to verify it fails**

Run: `cd agents/default && uv run pytest tests/test_registry.py -v`
Expected: FAIL — `app.registry` module does not exist.

- [ ] **Step 6: Implement RegistryClient**

```python
from __future__ import annotations

import asyncio
import logging

import httpx

from app.config import Settings

logger = logging.getLogger(__name__)


class RegistryError(Exception):
    pass


class RegistryClient:
    def __init__(
        self,
        settings: Settings,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._agent_name = settings.agent_name
        self._interval = settings.heartbeat_interval
        self._client = httpx.AsyncClient(
            base_url=settings.api_service_url,
            headers={"Authorization": f"Bearer {settings.provider_api_key}"},
            transport=transport,
        )

    async def register(self) -> None:
        response = await self._client.post(f"/agents/{self._agent_name}/registry")
        if response.status_code != 200:
            raise RegistryError(
                f"Registry failed: status={response.status_code} body={response.text}"
            )
        logger.info("Agent registered: %s", self._agent_name)

    async def heartbeat_loop(self) -> None:
        while True:
            try:
                response = await self._client.post(
                    f"/agents/{self._agent_name}/heartbeat"
                )
                if response.status_code != 200:
                    logger.warning(
                        "Heartbeat failed: status=%d", response.status_code
                    )
            except httpx.HTTPError as e:
                logger.warning("Heartbeat error: %s", e)
            await asyncio.sleep(self._interval)

    async def close(self) -> None:
        await self._client.aclose()
```

Write to `agents/default/app/registry.py`.

- [ ] **Step 7: Run test to verify it passes**

Run: `cd agents/default && uv run pytest tests/test_registry.py -v`
Expected: PASS (all 5 tests).

- [ ] **Step 8: Update main.py**

Replace entire `agents/default/app/main.py`:

```python
from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import FastAPI

from app.agent.chat import ChatAgent
from app.config import Settings
from app.kafka.consumer import TaskConsumer
from app.kafka.producer import ResultProducer
from app.logging import RequestLoggingMiddleware, setup_logging
from app.registry import RegistryClient
from app.routes.health import router as health_router


@asynccontextmanager
async def lifespan(app: FastAPI):
    load_dotenv()
    setup_logging()
    settings = Settings()

    registry = RegistryClient(settings)
    await registry.register()

    agent = ChatAgent(api_key=settings.google_api_key)
    producer = ResultProducer(bootstrap_servers=settings.kafka_bootstrap_servers)
    consumer = TaskConsumer(
        agent_id=settings.agent_id,
        bootstrap_servers=settings.kafka_bootstrap_servers,
        agent=agent,
        producer=producer,
    )

    await producer.start()
    await consumer.start()

    import asyncio

    hb_task = asyncio.create_task(registry.heartbeat_loop())

    yield

    hb_task.cancel()
    try:
        await hb_task
    except asyncio.CancelledError:
        pass
    await consumer.stop()
    await producer.stop()
    await registry.close()


def create_app() -> FastAPI:
    app = FastAPI(title="Bara Default Agent", lifespan=lifespan)
    app.add_middleware(RequestLoggingMiddleware)
    app.include_router(health_router)
    return app


app = create_app()
```

- [ ] **Step 9: Commit**

```bash
git add agents/default/app/config.py agents/default/app/registry.py agents/default/app/main.py agents/default/pyproject.toml agents/default/uv.lock agents/default/.env.example agents/default/tests/test_registry.py
git commit -m "feat(agent): add RegistryClient for HTTP-based registration and heartbeat"
```

---

## Task 4: Agent — Remove Kafka Heartbeat

**Files:**

- Delete: `agents/default/app/kafka/heartbeat.py`
- Modify: `agents/default/app/kafka/producer.py`
- Modify: `agents/default/app/models/messages.py`
- Delete: `agents/default/tests/test_heartbeat.py`
- Modify: `agents/default/tests/test_messages.py`
- Modify: `agents/default/tests/test_producer.py`
- Modify: `agents/default/tests/test_health.py`

- [ ] **Step 1: Delete HeartbeatLoop**

Delete: `agents/default/app/kafka/heartbeat.py`

- [ ] **Step 2: Remove send_heartbeat from producer**

In `agents/default/app/kafka/producer.py`:

Remove `HeartbeatMessage` from the import (line 7):

```python
from app.models.messages import TaskResult
```

Remove the `send_heartbeat` method (lines 30-32). The file should become:

```python
from __future__ import annotations

import logging

from aiokafka import AIOKafkaProducer

from app.models.messages import TaskResult

logger = logging.getLogger(__name__)


class ResultProducer:
    def __init__(self, bootstrap_servers: str) -> None:
        self._producer = AIOKafkaProducer(
            bootstrap_servers=bootstrap_servers,
        )

    async def start(self) -> None:
        await self._producer.start()
        logger.info("Kafka producer started")

    async def stop(self) -> None:
        await self._producer.stop()
        logger.info("Kafka producer stopped")

    async def send_result(self, topic: str, result: TaskResult) -> None:
        value = result.model_dump_json(exclude_none=True).encode()
        await self._producer.send_and_wait(topic, value=value)
```

- [ ] **Step 3: Remove HeartbeatMessage from models**

In `agents/default/app/models/messages.py`, remove the `HeartbeatMessage` class (lines 51-53). The file ends after `TaskResult`.

- [ ] **Step 4: Delete test_heartbeat.py**

Delete: `agents/default/tests/test_heartbeat.py`

- [ ] **Step 5: Update test_messages.py**

In `agents/default/tests/test_messages.py`:

Remove `HeartbeatMessage` from import (line 5):

```python
from app.models.messages import (
    Message,
    Part,
    TaskMessage,
    TaskResult,
    TaskStatus,
)
```

Remove the `test_heartbeat_message` test (lines 146-153).

- [ ] **Step 6: Update test_producer.py**

In `agents/default/tests/test_producer.py`:

Remove `HeartbeatMessage` from import (lines 7-12):

```python
from app.models.messages import (
    Message,
    Part,
    TaskResult,
    TaskStatus,
)
```

Remove the `test_send_heartbeat` test (lines 49-56).

- [ ] **Step 7: Update test_health.py**

Replace entire `agents/default/tests/test_health.py`:

```python
from unittest.mock import AsyncMock, patch

import pytest
from httpx import ASGITransport, AsyncClient

from app.main import create_app


@pytest.fixture
def app():
    with patch("app.main.ChatAgent"), \
         patch("app.main.ResultProducer") as mock_prod_cls, \
         patch("app.main.TaskConsumer") as mock_cons_cls, \
         patch("app.main.RegistryClient") as mock_reg_cls:
        mock_prod_cls.return_value = AsyncMock()
        mock_cons_cls.return_value = AsyncMock()
        mock_reg = AsyncMock()
        mock_reg.heartbeat_loop = AsyncMock()
        mock_reg_cls.return_value = mock_reg
        yield create_app()


@pytest.mark.asyncio
async def test_readiness(app):
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        resp = await client.get("/health/readiness")
    assert resp.status_code == 200
    assert resp.json()["status"] == "UP"


@pytest.mark.asyncio
async def test_liveness(app):
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        resp = await client.get("/health/liveness")
    assert resp.status_code == 200
    assert resp.json()["status"] == "UP"
```

- [ ] **Step 8: Run all agent tests**

Run: `cd agents/default && uv run pytest -v`
Expected: All tests PASS.

- [ ] **Step 9: Commit**

```bash
git add -A agents/default/
git commit -m "refactor(agent): remove Kafka heartbeat, switch to HTTP-based heartbeat"
```

---

## Task 5: Traefik Routes

**Files:**

- Modify: `infra/k8s/base/gateway/routes.yaml`

- [ ] **Step 1: Verify heartbeat route**

The `api-write` IngressRoute already matches `POST` method with forwardAuth. The new heartbeat endpoint (`POST /agents/{name}/heartbeat`) will be matched by the existing `api-write` route or the `api-agent-registry` route. Verify by checking that `POST` requests to `/api/core/agents/{name}/heartbeat` will hit the `api-write` or `api-agent-registry` IngressRoute.

The current `api-agent-registry` route matches `PathRegexp(\`/api/core/agents/[^/]+/registry\`)`. This does NOT match `/heartbeat`. The `api-write`route matches`PathPrefix(\`/api/core/agents\`) && (Method(\`POST\`) || Method(\`DELETE\`))`. This WILL match `POST /api/core/agents/{name}/heartbeat`.

No changes needed to `routes.yaml` — the existing `api-write` route already covers `POST` to heartbeat.

- [ ] **Step 2: Commit**

If no changes were needed, skip commit. Otherwise:

```bash
git add infra/k8s/base/gateway/routes.yaml
git commit -m "feat(infra): update routes for HTTP heartbeat"
```

---

## Task 6: Update Docs

**Files:**

- Modify: `docs/spec/api/agent-registry.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update agent-registry.md heartbeat section**

Read `docs/spec/api/agent-registry.md` and update the "Heartbeat Consumer" section. Replace the Kafka-based heartbeat description with HTTP-based heartbeat:

Find the section starting with `### Heartbeat Consumer` and replace it with:

```markdown
### Heartbeat

Agent가 HTTP `POST /agents/{agentName}/heartbeat`를 20초 간격으로 호출하여 생존을 알린다.

처리:

1. Traefik forwardAuth가 `X-Provider-Id` 주입
2. Redis `agent:registry:{agentName}` 존재 확인 → 없으면 404
3. MongoDB에서 Agent 조회 → 소유권 검증 → 불일치 시 403
4. Redis TTL 60초로 갱신

에러:

- 인증 실패 → 401
- Agent 미등록 → 404
- 소유권 불일치 → 403
```

- [ ] **Step 2: Update CLAUDE.md**

In `CLAUDE.md`, find the line mentioning heartbeat:

```
- **API Service** — Google OAuth → Access/Refresh Token 발급, Provider API Key 관리, Kafka OAUTHBEARER 토큰, Traefik forwardAuth (`GET /api/auth/validate`)
```

Update the API Service description to mention HTTP heartbeat instead of Kafka heartbeat. Find the API Service bullet and ensure it reflects:

- `HeartbeatAgentService` (HTTP) instead of `HeartbeatConsumer` (Kafka)

Also update the architecture note:

```
모든 Agent 통신은 Kafka를 통해 비동기로 처리. Redis로 Agent 상태 관리(heartbeat) 및 SSE 버퍼링.
```

to:

```
Agent 등록 및 heartbeat는 HTTP로 처리, 태스크/결과 통신은 Kafka를 통해 비동기로 처리. Redis로 Agent 상태 관리(heartbeat) 및 SSE 버퍼링.
```

- [ ] **Step 3: Run full test suite**

Run: `./gradlew :apps:api:test && cd agents/default && uv run pytest -v`
Expected: All tests PASS.

- [ ] **Step 4: Commit**

```bash
git add docs/spec/api/agent-registry.md CLAUDE.md
git commit -m "docs: update heartbeat docs from Kafka to HTTP-based approach"
```

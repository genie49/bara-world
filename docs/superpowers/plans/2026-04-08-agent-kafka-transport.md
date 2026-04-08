# Default Agent Kafka 전환 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Default Agent의 통신 방식을 HTTP에서 Kafka로 전환하여 `tasks.{agent-id}` 토픽에서 태스크를 수신하고 `result_topic`에 결과를 발행하며, 20초 간격으로 heartbeat를 보낸다.

**Architecture:** FastAPI는 헬스체크만 유지하고, Kafka consumer/producer를 lifespan에서 시작한다. `aiokafka`로 비동기 Kafka 통신을 처리하며, 기존 `ChatAgent`는 그대로 사용한다. HTTP 태스크 라우트와 AgentCard 라우트는 제거한다.

**Tech Stack:** Python 3.12+, aiokafka, FastAPI (헬스체크), LangChain, Pydantic v2

**중요:** 커밋 메시지에 Co-Authored-By 트레일러를 붙이지 마라. git commit 시 --no-verify 플래그를 사용하지 마라.

---

### Task 1: 의존성 변경 및 HTTP 라우트 제거

**Files:**
- Modify: `agents/default/pyproject.toml`
- Delete: `agents/default/app/routes/task.py`
- Delete: `agents/default/app/routes/agent_card.py`
- Delete: `agents/default/app/models/a2a.py`
- Delete: `agents/default/tests/test_task_route.py`
- Delete: `agents/default/tests/test_stream_route.py`
- Delete: `agents/default/tests/test_agent_card_route.py`
- Delete: `agents/default/tests/test_models.py`
- Modify: `agents/default/app/main.py`

- [ ] **Step 1: pyproject.toml 수정 — sse-starlette 제거, aiokafka 추가**

`agents/default/pyproject.toml` 전체:

```toml
[project]
name = "bara-default-agent"
version = "0.1.0"
description = "Bara 플랫폼 기본 범용 대화 에이전트"
requires-python = ">=3.12"
dependencies = [
    "fastapi>=0.115.0",
    "uvicorn[standard]>=0.30.0",
    "langchain>=1.0,<2.0",
    "langchain-core>=1.0,<2.0",
    "langchain-google-genai>=2.0.0",
    "langgraph>=1.0,<2.0",
    "pydantic>=2.0",
    "pydantic-settings>=2.0",
    "python-dotenv>=1.0.0",
    "aiokafka>=0.12.0",
]

[dependency-groups]
dev = [
    "pytest>=8.0",
    "pytest-asyncio>=0.24.0",
    "httpx>=0.27.0",
]

[tool.pytest.ini_options]
asyncio_mode = "auto"
testpaths = ["tests"]
```

- [ ] **Step 2: HTTP 라우트 파일 삭제**

```bash
rm agents/default/app/routes/task.py
rm agents/default/app/routes/agent_card.py
rm agents/default/app/models/a2a.py
rm agents/default/tests/test_task_route.py
rm agents/default/tests/test_stream_route.py
rm agents/default/tests/test_agent_card_route.py
rm agents/default/tests/test_models.py
```

- [ ] **Step 3: main.py에서 삭제된 라우트 import 제거**

`agents/default/app/main.py` 전체:

```python
from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import FastAPI

from app.agent.chat import ChatAgent
from app.config import Settings
from app.logging import RequestLoggingMiddleware, setup_logging
from app.routes.health import router as health_router


@asynccontextmanager
async def lifespan(app: FastAPI):
    load_dotenv()
    setup_logging()
    settings = Settings()
    app.state.agent = ChatAgent(api_key=settings.google_api_key)
    yield


def create_app() -> FastAPI:
    app = FastAPI(title="Bara Default Agent", lifespan=lifespan)
    app.add_middleware(RequestLoggingMiddleware)
    app.include_router(health_router)
    return app


app = create_app()
```

- [ ] **Step 4: 의존성 재설치**

Run: `cd agents/default && uv sync`

- [ ] **Step 5: 기존 테스트 통과 확인**

Run: `cd agents/default && uv run python -m pytest -v`
Expected: 남은 테스트만 통과 (test_chat.py, test_config.py, test_health.py)

- [ ] **Step 6: 커밋**

```bash
git add -A agents/default/
git commit -m "refactor(agent): remove HTTP task routes, prepare for Kafka transport"
```

---

### Task 2: Settings 확장 및 Kafka 메시지 모델

**Files:**
- Modify: `agents/default/app/config.py`
- Create: `agents/default/app/models/messages.py`
- Create: `agents/default/tests/test_messages.py`
- Modify: `agents/default/tests/test_config.py`
- Modify: `agents/default/.env.example`

- [ ] **Step 1: 테스트 작성 — Settings**

`agents/default/tests/test_config.py` 전체:

```python
from app.config import Settings


def test_settings_defaults():
    settings = Settings(google_api_key="test-key")
    assert settings.agent_id == "default-agent"
    assert settings.kafka_bootstrap_servers == "localhost:9092"
    assert settings.port == 8090


def test_settings_custom_values():
    settings = Settings(
        google_api_key="custom-key",
        agent_id="agent-999",
        kafka_bootstrap_servers="kafka:29092",
        port=9000,
    )
    assert settings.google_api_key == "custom-key"
    assert settings.agent_id == "agent-999"
    assert settings.kafka_bootstrap_servers == "kafka:29092"
    assert settings.port == 9000
```

- [ ] **Step 2: 테스트 작성 — 메시지 모델**

`agents/default/app/models/__init__.py` — 이미 존재하면 스킵

`agents/default/tests/test_messages.py`:

```python
import json

from app.models.messages import (
    HeartbeatMessage,
    Message,
    Part,
    TaskMessage,
    TaskResult,
    TaskStatus,
)


def test_part_text_only():
    part = Part(text="안녕하세요")
    data = json.loads(part.model_dump_json(exclude_none=True))
    assert data == {"text": "안녕하세요"}
    assert "type" not in data


def test_part_with_metadata():
    part = Part(text="hello", metadata={"key": "val"})
    data = json.loads(part.model_dump_json(exclude_none=True))
    assert data["text"] == "hello"
    assert data["metadata"] == {"key": "val"}


def test_message_required_fields():
    msg = Message(
        message_id="msg-001",
        role="user",
        parts=[Part(text="hi")],
    )
    data = json.loads(msg.model_dump_json(exclude_none=True))
    assert data["message_id"] == "msg-001"
    assert data["role"] == "user"
    assert data["parts"] == [{"text": "hi"}]
    assert "context_id" not in data


def test_message_with_optional_fields():
    msg = Message(
        message_id="msg-002",
        role="agent",
        parts=[Part(text="response")],
        context_id="ctx-abc",
        task_id="task-123",
        metadata={"foo": "bar"},
        extensions=["ext-1"],
        reference_task_ids=["task-100"],
    )
    data = json.loads(msg.model_dump_json(exclude_none=True))
    assert data["context_id"] == "ctx-abc"
    assert data["task_id"] == "task-123"
    assert data["metadata"] == {"foo": "bar"}
    assert data["extensions"] == ["ext-1"]
    assert data["reference_task_ids"] == ["task-100"]


def test_parse_task_message():
    raw = {
        "task_id": "task-123",
        "context_id": "ctx-abc",
        "user_id": "user-456",
        "request_id": "req-789",
        "result_topic": "results.api",
        "allowed_agents": ["agent-001"],
        "message": {
            "message_id": "msg-001",
            "role": "user",
            "parts": [{"text": "안녕하세요"}],
        },
    }
    task_msg = TaskMessage.model_validate(raw)
    assert task_msg.task_id == "task-123"
    assert task_msg.result_topic == "results.api"
    assert task_msg.allowed_agents == ["agent-001"]
    assert task_msg.message.message_id == "msg-001"
    assert task_msg.message.parts[0].text == "안녕하세요"


def test_parse_task_message_without_optional():
    raw = {
        "task_id": "task-456",
        "user_id": "user-1",
        "request_id": "req-1",
        "result_topic": "results.api",
        "message": {
            "message_id": "msg-1",
            "role": "user",
            "parts": [{"text": "hello"}],
        },
    }
    task_msg = TaskMessage.model_validate(raw)
    assert task_msg.context_id is None
    assert task_msg.allowed_agents is None


def test_serialize_task_result():
    result = TaskResult(
        task_id="task-123",
        context_id="ctx-abc",
        user_id="user-456",
        request_id="req-789",
        agent_id="agent-001",
        status=TaskStatus(
            state="completed",
            message=Message(
                message_id="msg-002",
                role="agent",
                parts=[Part(text="응답입니다")],
            ),
            timestamp="2026-04-08T17:00:00Z",
        ),
        final=True,
    )
    data = json.loads(result.model_dump_json(exclude_none=True))
    assert data["task_id"] == "task-123"
    assert data["agent_id"] == "agent-001"
    assert data["status"]["state"] == "completed"
    assert data["status"]["message"]["parts"][0]["text"] == "응답입니다"
    assert data["status"]["timestamp"] == "2026-04-08T17:00:00Z"
    assert data["final"] is True


def test_serialize_task_result_failed():
    result = TaskResult(
        task_id="task-err",
        user_id="user-1",
        request_id="req-1",
        agent_id="agent-001",
        status=TaskStatus(
            state="failed",
            message=Message(
                message_id="msg-err",
                role="agent",
                parts=[Part(text="LLM 호출 실패")],
            ),
        ),
        final=True,
    )
    data = json.loads(result.model_dump_json(exclude_none=True))
    assert data["status"]["state"] == "failed"
    assert data["final"] is True


def test_heartbeat_message():
    hb = HeartbeatMessage(
        agent_id="agent-001",
        timestamp="2026-04-08T17:00:00Z",
    )
    data = json.loads(hb.model_dump_json())
    assert data["agent_id"] == "agent-001"
    assert data["timestamp"] == "2026-04-08T17:00:00Z"
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `cd agents/default && uv run python -m pytest tests/test_messages.py tests/test_config.py -v`
Expected: FAIL

- [ ] **Step 4: config.py 구현**

`agents/default/app/config.py`:

```python
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    google_api_key: str = ""
    agent_id: str = "default-agent"
    kafka_bootstrap_servers: str = "localhost:9092"
    port: int = 8090

    model_config = {"env_file": ".env", "env_file_encoding": "utf-8"}
```

- [ ] **Step 5: messages.py 구현**

`agents/default/app/models/messages.py`:

```python
from __future__ import annotations

from typing import Any

from pydantic import BaseModel


class Part(BaseModel):
    text: str | None = None
    metadata: dict[str, Any] | None = None
    media_type: str | None = None


class Message(BaseModel):
    message_id: str
    role: str
    parts: list[Part]
    context_id: str | None = None
    task_id: str | None = None
    metadata: dict[str, Any] | None = None
    extensions: list[str] | None = None
    reference_task_ids: list[str] | None = None


class TaskMessage(BaseModel):
    task_id: str
    context_id: str | None = None
    user_id: str
    request_id: str
    result_topic: str
    allowed_agents: list[str] | None = None
    message: Message


class TaskStatus(BaseModel):
    state: str
    message: Message | None = None
    timestamp: str | None = None


class TaskResult(BaseModel):
    task_id: str
    context_id: str | None = None
    user_id: str
    request_id: str
    agent_id: str
    status: TaskStatus
    final: bool


class HeartbeatMessage(BaseModel):
    agent_id: str
    timestamp: str
```

- [ ] **Step 6: .env.example 수정**

`agents/default/.env.example`:

```env
GOOGLE_API_KEY=your-gemini-api-key-here
AGENT_ID=default-agent
KAFKA_BOOTSTRAP_SERVERS=localhost:9092
PORT=8090
```

- [ ] **Step 7: 테스트 통과 확인**

Run: `cd agents/default && uv run python -m pytest tests/test_messages.py tests/test_config.py -v`
Expected: 전체 통과

- [ ] **Step 8: 커밋**

```bash
git add agents/default/app/config.py agents/default/app/models/messages.py agents/default/tests/test_messages.py agents/default/tests/test_config.py agents/default/.env.example
git commit -m "feat(agent): add Kafka message models and Settings with agent_id"
```

---

### Task 3: ResultProducer

**Files:**
- Create: `agents/default/app/kafka/__init__.py`
- Create: `agents/default/app/kafka/producer.py`
- Create: `agents/default/tests/test_producer.py`

- [ ] **Step 1: 테스트 작성**

`agents/default/app/kafka/__init__.py` — 빈 파일

`agents/default/tests/test_producer.py`:

```python
from unittest.mock import AsyncMock, patch

import pytest

from app.kafka.producer import ResultProducer
from app.models.messages import (
    HeartbeatMessage,
    Message,
    Part,
    TaskResult,
    TaskStatus,
)


@pytest.fixture
def producer():
    with patch("app.kafka.producer.AIOKafkaProducer") as mock_cls:
        mock_kafka = AsyncMock()
        mock_cls.return_value = mock_kafka
        p = ResultProducer(bootstrap_servers="localhost:9092")
        p._producer = mock_kafka
        yield p


@pytest.mark.asyncio
async def test_send_result(producer: ResultProducer):
    result = TaskResult(
        task_id="task-1",
        user_id="user-1",
        request_id="req-1",
        agent_id="agent-001",
        status=TaskStatus(
            state="completed",
            message=Message(
                message_id="msg-1",
                role="agent",
                parts=[Part(text="응답")],
            ),
        ),
        final=True,
    )
    await producer.send_result("results.api", result)
    producer._producer.send_and_wait.assert_called_once()
    call_args = producer._producer.send_and_wait.call_args
    assert call_args[0][0] == "results.api"
    assert b"task-1" in call_args[1]["value"]


@pytest.mark.asyncio
async def test_send_heartbeat(producer: ResultProducer):
    hb = HeartbeatMessage(agent_id="agent-001", timestamp="2026-04-08T17:00:00Z")
    await producer.send_heartbeat(hb)
    producer._producer.send_and_wait.assert_called_once()
    call_args = producer._producer.send_and_wait.call_args
    assert call_args[0][0] == "heartbeat"
    assert b"agent-001" in call_args[1]["value"]


@pytest.mark.asyncio
async def test_start_and_stop(producer: ResultProducer):
    await producer.start()
    producer._producer.start.assert_called_once()
    await producer.stop()
    producer._producer.stop.assert_called_once()
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd agents/default && uv run python -m pytest tests/test_producer.py -v`
Expected: FAIL

- [ ] **Step 3: producer.py 구현**

`agents/default/app/kafka/producer.py`:

```python
from __future__ import annotations

import logging

from aiokafka import AIOKafkaProducer

from app.models.messages import HeartbeatMessage, TaskResult

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

    async def send_heartbeat(self, msg: HeartbeatMessage) -> None:
        value = msg.model_dump_json().encode()
        await self._producer.send_and_wait("heartbeat", value=value)
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd agents/default && uv run python -m pytest tests/test_producer.py -v`
Expected: 3 passed

- [ ] **Step 5: 커밋**

```bash
git add agents/default/app/kafka/ agents/default/tests/test_producer.py
git commit -m "feat(agent): add ResultProducer for Kafka message publishing"
```

---

### Task 4: HeartbeatLoop

**Files:**
- Create: `agents/default/app/kafka/heartbeat.py`
- Create: `agents/default/tests/test_heartbeat.py`

- [ ] **Step 1: 테스트 작성**

`agents/default/tests/test_heartbeat.py`:

```python
import asyncio
from unittest.mock import AsyncMock

import pytest

from app.kafka.heartbeat import HeartbeatLoop


@pytest.mark.asyncio
async def test_heartbeat_sends_on_start():
    producer = AsyncMock()
    loop = HeartbeatLoop(agent_id="agent-001", producer=producer, interval=0.1)
    await loop.start()
    await asyncio.sleep(0.25)
    await loop.stop()
    assert producer.send_heartbeat.call_count >= 2


@pytest.mark.asyncio
async def test_heartbeat_stop_cancels_task():
    producer = AsyncMock()
    loop = HeartbeatLoop(agent_id="agent-001", producer=producer, interval=0.1)
    await loop.start()
    await loop.stop()
    assert loop._task is None or loop._task.cancelled()


@pytest.mark.asyncio
async def test_heartbeat_message_contains_agent_id():
    producer = AsyncMock()
    loop = HeartbeatLoop(agent_id="agent-test", producer=producer, interval=0.1)
    await loop.start()
    await asyncio.sleep(0.15)
    await loop.stop()
    call_args = producer.send_heartbeat.call_args
    hb_msg = call_args[0][0]
    assert hb_msg.agent_id == "agent-test"
    assert hb_msg.timestamp is not None
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd agents/default && uv run python -m pytest tests/test_heartbeat.py -v`
Expected: FAIL

- [ ] **Step 3: heartbeat.py 구현**

`agents/default/app/kafka/heartbeat.py`:

```python
from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone

from app.kafka.producer import ResultProducer
from app.models.messages import HeartbeatMessage

logger = logging.getLogger(__name__)

HEARTBEAT_INTERVAL = 20


class HeartbeatLoop:
    def __init__(
        self,
        agent_id: str,
        producer: ResultProducer,
        interval: float = HEARTBEAT_INTERVAL,
    ) -> None:
        self._agent_id = agent_id
        self._producer = producer
        self._interval = interval
        self._task: asyncio.Task | None = None

    async def start(self) -> None:
        self._task = asyncio.create_task(self._loop())
        logger.info("Heartbeat loop started (interval=%ss)", self._interval)

    async def stop(self) -> None:
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None
        logger.info("Heartbeat loop stopped")

    async def _loop(self) -> None:
        while True:
            try:
                msg = HeartbeatMessage(
                    agent_id=self._agent_id,
                    timestamp=datetime.now(timezone.utc).isoformat(),
                )
                await self._producer.send_heartbeat(msg)
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.exception("Failed to send heartbeat")
            await asyncio.sleep(self._interval)
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd agents/default && uv run python -m pytest tests/test_heartbeat.py -v`
Expected: 3 passed

- [ ] **Step 5: 커밋**

```bash
git add agents/default/app/kafka/heartbeat.py agents/default/tests/test_heartbeat.py
git commit -m "feat(agent): add HeartbeatLoop with 20s interval"
```

---

### Task 5: TaskConsumer

**Files:**
- Create: `agents/default/app/kafka/consumer.py`
- Create: `agents/default/tests/test_consumer.py`

- [ ] **Step 1: 테스트 작성**

`agents/default/tests/test_consumer.py`:

```python
import json
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.kafka.consumer import TaskConsumer
from app.models.messages import Message, Part, TaskMessage


def _make_raw_message(
    text="안녕",
    task_id="task-1",
    context_id="ctx-1",
    user_id="user-1",
    request_id="req-1",
    result_topic="results.api",
):
    msg = {
        "task_id": task_id,
        "context_id": context_id,
        "user_id": user_id,
        "request_id": request_id,
        "result_topic": result_topic,
        "message": {
            "message_id": "msg-1",
            "role": "user",
            "parts": [{"text": text}],
        },
    }
    return json.dumps(msg).encode()


@pytest.fixture
def consumer():
    agent = AsyncMock()
    agent.invoke.return_value = "응답입니다"
    producer = AsyncMock()
    c = TaskConsumer(
        agent_id="agent-001",
        bootstrap_servers="localhost:9092",
        agent=agent,
        producer=producer,
    )
    return c


@pytest.mark.asyncio
async def test_process_message_success(consumer: TaskConsumer):
    raw = _make_raw_message()
    await consumer._process_message(raw)

    consumer._agent.invoke.assert_called_once_with("안녕", context_id="ctx-1")
    consumer._producer.send_result.assert_called_once()
    call_args = consumer._producer.send_result.call_args
    assert call_args[0][0] == "results.api"
    result = call_args[0][1]
    assert result.task_id == "task-1"
    assert result.status.state == "completed"
    assert result.status.message.parts[0].text == "응답입니다"
    assert result.final is True
    assert result.agent_id == "agent-001"


@pytest.mark.asyncio
async def test_process_message_llm_failure(consumer: TaskConsumer):
    consumer._agent.invoke.side_effect = Exception("LLM error")
    raw = _make_raw_message()
    await consumer._process_message(raw)

    consumer._producer.send_result.assert_called_once()
    call_args = consumer._producer.send_result.call_args
    result = call_args[0][1]
    assert result.status.state == "failed"
    assert result.final is True


@pytest.mark.asyncio
async def test_process_message_invalid_json(consumer: TaskConsumer):
    await consumer._process_message(b"not json")
    consumer._agent.invoke.assert_not_called()
    consumer._producer.send_result.assert_not_called()


@pytest.mark.asyncio
async def test_process_message_extracts_text_from_parts(consumer: TaskConsumer):
    raw = _make_raw_message(text="여러 파트 테스트")
    await consumer._process_message(raw)
    consumer._agent.invoke.assert_called_once_with("여러 파트 테스트", context_id="ctx-1")


@pytest.mark.asyncio
async def test_process_message_without_context_id(consumer: TaskConsumer):
    msg = {
        "task_id": "task-2",
        "user_id": "user-1",
        "request_id": "req-1",
        "result_topic": "results.api",
        "message": {
            "message_id": "msg-1",
            "role": "user",
            "parts": [{"text": "hi"}],
        },
    }
    raw = json.dumps(msg).encode()
    await consumer._process_message(raw)
    consumer._agent.invoke.assert_called_once_with("hi", context_id=None)
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd agents/default && uv run python -m pytest tests/test_consumer.py -v`
Expected: FAIL

- [ ] **Step 3: consumer.py 구현**

`agents/default/app/kafka/consumer.py`:

```python
from __future__ import annotations

import asyncio
import json
import logging
import uuid
from datetime import datetime, timezone

from aiokafka import AIOKafkaConsumer

from app.agent.chat import ChatAgent
from app.kafka.producer import ResultProducer
from app.logging import WideEvent
from app.models.messages import (
    Message,
    Part,
    TaskMessage,
    TaskResult,
    TaskStatus,
)

logger = logging.getLogger(__name__)


class TaskConsumer:
    def __init__(
        self,
        agent_id: str,
        bootstrap_servers: str,
        agent: ChatAgent,
        producer: ResultProducer,
    ) -> None:
        self._agent_id = agent_id
        self._bootstrap_servers = bootstrap_servers
        self._agent = agent
        self._producer = producer
        self._consumer: AIOKafkaConsumer | None = None
        self._task: asyncio.Task | None = None

    async def start(self) -> None:
        topic = f"tasks.{self._agent_id}"
        self._consumer = AIOKafkaConsumer(
            topic,
            bootstrap_servers=self._bootstrap_servers,
            group_id=f"agent-{self._agent_id}",
        )
        await self._consumer.start()
        self._task = asyncio.create_task(self._consume_loop())
        logger.info("TaskConsumer started on topic=%s", topic)

    async def stop(self) -> None:
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None
        if self._consumer:
            await self._consumer.stop()
        logger.info("TaskConsumer stopped")

    async def _consume_loop(self) -> None:
        async for msg in self._consumer:
            await self._process_message(msg.value)

    async def _process_message(self, raw: bytes) -> None:
        try:
            data = json.loads(raw)
            task_msg = TaskMessage.model_validate(data)
        except Exception:
            logger.warning("Failed to deserialize task message, skipping")
            return

        WideEvent.put("task_id", task_msg.task_id)
        WideEvent.put("context_id", task_msg.context_id)
        WideEvent.put("user_id", task_msg.user_id)
        WideEvent.put("request_id", task_msg.request_id)

        text = task_msg.message.parts[0].text if task_msg.message.parts else ""

        try:
            response_text = await self._agent.invoke(
                text, context_id=task_msg.context_id
            )
            status = TaskStatus(
                state="completed",
                message=Message(
                    message_id=str(uuid.uuid4()),
                    role="agent",
                    parts=[Part(text=response_text)],
                ),
                timestamp=datetime.now(timezone.utc).isoformat(),
            )
            WideEvent.put("outcome", "success")
            WideEvent.message("task completed")
        except Exception:
            logger.exception("LLM invocation failed for task=%s", task_msg.task_id)
            status = TaskStatus(
                state="failed",
                message=Message(
                    message_id=str(uuid.uuid4()),
                    role="agent",
                    parts=[Part(text="Internal error: LLM invocation failed")],
                ),
                timestamp=datetime.now(timezone.utc).isoformat(),
            )
            WideEvent.put("outcome", "error")
            WideEvent.message("task failed")

        result = TaskResult(
            task_id=task_msg.task_id,
            context_id=task_msg.context_id,
            user_id=task_msg.user_id,
            request_id=task_msg.request_id,
            agent_id=self._agent_id,
            status=status,
            final=True,
        )

        try:
            await self._producer.send_result(task_msg.result_topic, result)
        except Exception:
            logger.exception("Failed to send result for task=%s", task_msg.task_id)
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd agents/default && uv run python -m pytest tests/test_consumer.py -v`
Expected: 5 passed

- [ ] **Step 5: 전체 테스트 통과 확인**

Run: `cd agents/default && uv run python -m pytest -v`
Expected: 전체 통과

- [ ] **Step 6: 커밋**

```bash
git add agents/default/app/kafka/consumer.py agents/default/tests/test_consumer.py
git commit -m "feat(agent): add TaskConsumer for Kafka task processing"
```

---

### Task 6: main.py Kafka lifespan 통합

**Files:**
- Modify: `agents/default/app/main.py`
- Modify: `agents/default/tests/test_health.py`

- [ ] **Step 1: main.py 수정**

`agents/default/app/main.py` 전체:

```python
from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import FastAPI

from app.agent.chat import ChatAgent
from app.config import Settings
from app.kafka.consumer import TaskConsumer
from app.kafka.heartbeat import HeartbeatLoop
from app.kafka.producer import ResultProducer
from app.logging import RequestLoggingMiddleware, setup_logging
from app.routes.health import router as health_router


@asynccontextmanager
async def lifespan(app: FastAPI):
    load_dotenv()
    setup_logging()
    settings = Settings()

    agent = ChatAgent(api_key=settings.google_api_key)
    producer = ResultProducer(bootstrap_servers=settings.kafka_bootstrap_servers)
    consumer = TaskConsumer(
        agent_id=settings.agent_id,
        bootstrap_servers=settings.kafka_bootstrap_servers,
        agent=agent,
        producer=producer,
    )
    heartbeat = HeartbeatLoop(agent_id=settings.agent_id, producer=producer)

    await producer.start()
    await consumer.start()
    await heartbeat.start()

    yield

    await heartbeat.stop()
    await consumer.stop()
    await producer.stop()


def create_app() -> FastAPI:
    app = FastAPI(title="Bara Default Agent", lifespan=lifespan)
    app.add_middleware(RequestLoggingMiddleware)
    app.include_router(health_router)
    return app


app = create_app()
```

- [ ] **Step 2: 헬스체크 테스트 수정 (Kafka mock)**

`agents/default/tests/test_health.py`:

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
         patch("app.main.HeartbeatLoop") as mock_hb_cls:
        mock_prod_cls.return_value = AsyncMock()
        mock_cons_cls.return_value = AsyncMock()
        mock_hb_cls.return_value = AsyncMock()
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

- [ ] **Step 3: 전체 테스트 통과 확인**

Run: `cd agents/default && uv run python -m pytest -v`
Expected: 전체 통과

- [ ] **Step 4: uv.lock 갱신**

Run: `cd agents/default && uv sync`

- [ ] **Step 5: 커밋**

```bash
git add agents/default/app/main.py agents/default/tests/test_health.py agents/default/uv.lock
git commit -m "feat(agent): integrate Kafka consumer, producer, heartbeat in lifespan"
```

---

### Task 7: 통합 테스트 (로컬 Kafka)

**Files:**
- 없음 (수동 테스트)

- [ ] **Step 1: 로컬 Kafka 실행**

```bash
./scripts/infra.sh up
```

Expected: MongoDB, Redis, Kafka 컨테이너 시작

- [ ] **Step 2: .env 설정**

```bash
cd agents/default
cp .env.example .env
# .env에 GOOGLE_API_KEY, AGENT_ID 입력
```

- [ ] **Step 3: Agent 실행**

```bash
cd agents/default
uv run uvicorn app.main:app --port 8090 --reload
```

Expected: 로그에 `Kafka producer started`, `TaskConsumer started on topic=tasks.{agent-id}`, `Heartbeat loop started` 출력

- [ ] **Step 4: 헬스체크 확인**

```bash
curl -s http://localhost:8090/health/readiness
```

Expected: `{"status":"UP"}`

- [ ] **Step 5: Kafka 토픽에 테스트 메시지 발행**

```bash
echo '{"task_id":"task-test","context_id":"ctx-1","user_id":"user-1","request_id":"req-1","result_topic":"results.api","message":{"message_id":"msg-1","role":"user","parts":[{"text":"안녕하세요"}]}}' | docker exec -i $(docker ps -qf name=kafka) /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server localhost:9092 --topic tasks.default-agent
```

- [ ] **Step 6: 결과 토픽에서 응답 확인**

```bash
docker exec $(docker ps -qf name=kafka) /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic results.api --from-beginning --max-messages 1
```

Expected: `{"task_id":"task-test","status":{"state":"completed","message":{"message_id":"...","role":"agent","parts":[{"text":"..."}]}...},"final":true}` 형태의 JSON

- [ ] **Step 7: heartbeat 토픽 확인**

```bash
docker exec $(docker ps -qf name=kafka) /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic heartbeat --from-beginning --max-messages 3
```

Expected: 20초 간격으로 `{"agent_id":"default-agent","timestamp":"..."}` 메시지 3개

- [ ] **Step 8: 서버 로그 확인**

콘솔에서:
- 태스크 처리 로그: `task_id`, `user_id`, `outcome=success`
- heartbeat 에러 없음
- 정상 종료 시 `TaskConsumer stopped`, `Heartbeat loop stopped`, `Kafka producer stopped`

- [ ] **Step 9: 전체 자동 테스트 최종 확인**

Run: `cd agents/default && uv run python -m pytest -v`
Expected: 전체 통과

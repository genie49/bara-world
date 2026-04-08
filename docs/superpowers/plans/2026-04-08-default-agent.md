# Bara Default Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gemini Flash Lite 기반 범용 대화 Agent를 FastAPI + LangChain으로 구현하여 A2A JSON-RPC 2.0 프로토콜로 동기/스트리밍 응답을 제공한다.

**Architecture:** FastAPI가 A2A JSON-RPC 요청을 받아 LangChain `ChatGoogleGenerativeAI`로 처리하고, 동기(`POST /`) 및 SSE 스트리밍(`POST /stream`) 응답을 반환한다. 대화 이력은 `context_id` 기반 인메모리 dict로 관리한다.

**Tech Stack:** Python 3.12+, FastAPI, Uvicorn, LangChain 1.0+, langchain-google-genai, Pydantic v2, pydantic-settings, python-dotenv, pytest, httpx

**중요:** 커밋 메시지에 Co-Authored-By 트레일러를 붙이지 마라. git commit 시 --no-verify 플래그를 사용하지 마라.

---

### Task 1: 프로젝트 스캐폴딩

**Files:**
- Create: `agents/default/pyproject.toml`
- Create: `agents/default/.env.example`
- Create: `agents/default/app/__init__.py`

- [ ] **Step 1: pyproject.toml 생성**

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
    "pydantic>=2.0",
    "pydantic-settings>=2.0",
    "python-dotenv>=1.0.0",
    "sse-starlette>=2.0.0",
]

[project.optional-dependencies]
dev = [
    "pytest>=8.0",
    "pytest-asyncio>=0.24.0",
    "httpx>=0.27.0",
]

[build-system]
requires = ["setuptools>=75.0"]
build-backend = "setuptools.backends._legacy:_Backend"

[tool.pytest.ini_options]
asyncio_mode = "auto"
testpaths = ["tests"]
```

- [ ] **Step 2: .env.example 생성**

```env
GOOGLE_API_KEY=your-gemini-api-key-here
MODEL_NAME=gemini-2.5-flash-lite-preview-06-17
PORT=8090
```

- [ ] **Step 3: app/__init__.py 생성**

```python
```

(빈 파일)

- [ ] **Step 4: 의존성 설치 및 확인**

Run: `cd agents/default && pip install -e ".[dev]"`
Expected: 정상 설치 완료

- [ ] **Step 5: 커밋**

```bash
git add agents/default/pyproject.toml agents/default/.env.example agents/default/app/__init__.py
git commit -m "chore(agent): scaffold default agent project"
```

---

### Task 2: 환경 설정 (config)

**Files:**
- Create: `agents/default/app/config.py`
- Create: `agents/default/tests/__init__.py`
- Create: `agents/default/tests/test_config.py`

- [ ] **Step 1: 테스트 작성**

`agents/default/tests/__init__.py` — 빈 파일

`agents/default/tests/test_config.py`:

```python
import os

from app.config import Settings


def test_settings_defaults():
    os.environ.setdefault("GOOGLE_API_KEY", "test-key")
    settings = Settings()
    assert settings.model_name == "gemini-2.5-flash-lite-preview-06-17"
    assert settings.port == 8090


def test_settings_custom_values():
    settings = Settings(
        google_api_key="custom-key",
        model_name="gemini-2.0-flash",
        port=9000,
    )
    assert settings.google_api_key == "custom-key"
    assert settings.model_name == "gemini-2.0-flash"
    assert settings.port == 9000
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd agents/default && python -m pytest tests/test_config.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.config'`

- [ ] **Step 3: config.py 구현**

`agents/default/app/config.py`:

```python
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    google_api_key: str = ""
    model_name: str = "gemini-2.5-flash-lite-preview-06-17"
    port: int = 8090

    model_config = {"env_file": ".env", "env_file_encoding": "utf-8"}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd agents/default && python -m pytest tests/test_config.py -v`
Expected: 2 passed

- [ ] **Step 5: 커밋**

```bash
git add agents/default/app/config.py agents/default/tests/
git commit -m "feat(agent): add Settings config with pydantic-settings"
```

---

### Task 3: A2A Pydantic 모델

**Files:**
- Create: `agents/default/app/models/__init__.py`
- Create: `agents/default/app/models/a2a.py`
- Create: `agents/default/tests/test_models.py`

- [ ] **Step 1: 테스트 작성**

`agents/default/app/models/__init__.py` — 빈 파일

`agents/default/tests/test_models.py`:

```python
import json

from app.models.a2a import (
    A2AMessage,
    AgentCard,
    JsonRpcError,
    JsonRpcRequest,
    JsonRpcResponse,
    MessagePart,
    TaskResult,
    TaskSendParams,
    TaskStatus,
)


def test_parse_task_send_request():
    raw = {
        "jsonrpc": "2.0",
        "id": "req-1",
        "method": "tasks/send",
        "params": {
            "id": "task-123",
            "message": {
                "role": "user",
                "parts": [{"type": "text", "text": "안녕하세요"}],
            },
            "contextId": "ctx-abc",
        },
    }
    req = JsonRpcRequest.model_validate(raw)
    assert req.id == "req-1"
    assert req.method == "tasks/send"
    assert req.params.id == "task-123"
    assert req.params.message.role == "user"
    assert req.params.message.parts[0].text == "안녕하세요"
    assert req.params.context_id == "ctx-abc"


def test_parse_request_without_context_id():
    raw = {
        "jsonrpc": "2.0",
        "id": "req-2",
        "method": "tasks/send",
        "params": {
            "id": "task-456",
            "message": {
                "role": "user",
                "parts": [{"type": "text", "text": "hello"}],
            },
        },
    }
    req = JsonRpcRequest.model_validate(raw)
    assert req.params.context_id is None


def test_serialize_response():
    resp = JsonRpcResponse(
        id="req-1",
        result=TaskResult(
            id="task-123",
            context_id="ctx-abc",
            status=TaskStatus(
                state="completed",
                message=A2AMessage(
                    role="agent",
                    parts=[MessagePart(type="text", text="응답입니다")],
                ),
            ),
            final=True,
        ),
    )
    data = json.loads(resp.model_dump_json(by_alias=True))
    assert data["jsonrpc"] == "2.0"
    assert data["result"]["contextId"] == "ctx-abc"
    assert data["result"]["status"]["state"] == "completed"
    assert data["result"]["final"] is True


def test_serialize_error_response():
    err = JsonRpcError(
        id="req-1",
        error={"code": -32601, "message": "Method not found"},
    )
    data = json.loads(err.model_dump_json(by_alias=True))
    assert data["error"]["code"] == -32601


def test_agent_card_serialization():
    card = AgentCard(
        name="Bara Default Agent",
        description="테스트 에이전트",
        version="0.1.0",
        default_input_modes=["text"],
        default_output_modes=["text"],
        capabilities={"streaming": True, "pushNotifications": False},
        skills=[
            {
                "id": "chat",
                "name": "대화",
                "description": "자연어 대화를 수행합니다",
                "tags": ["chat"],
                "examples": ["안녕하세요"],
            }
        ],
    )
    data = json.loads(card.model_dump_json(by_alias=True))
    assert data["defaultInputModes"] == ["text"]
    assert data["capabilities"]["streaming"] is True
    assert data["skills"][0]["id"] == "chat"
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd agents/default && python -m pytest tests/test_models.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.models'`

- [ ] **Step 3: A2A 모델 구현**

`agents/default/app/models/a2a.py`:

```python
from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class MessagePart(BaseModel):
    type: str = "text"
    text: str


class A2AMessage(BaseModel):
    role: str
    parts: list[MessagePart]


class TaskSendParams(BaseModel):
    id: str
    message: A2AMessage
    context_id: str | None = Field(default=None, alias="contextId")

    model_config = {"populate_by_name": True}


class JsonRpcRequest(BaseModel):
    jsonrpc: str = "2.0"
    id: str
    method: str
    params: TaskSendParams


class TaskStatus(BaseModel):
    state: str
    message: A2AMessage


class TaskResult(BaseModel):
    id: str
    context_id: str | None = Field(default=None, alias="contextId")
    status: TaskStatus
    final: bool

    model_config = {"populate_by_name": True}


class JsonRpcResponse(BaseModel):
    jsonrpc: str = "2.0"
    id: str
    result: TaskResult

    model_config = {"serialize_by_alias": True}


class JsonRpcError(BaseModel):
    jsonrpc: str = "2.0"
    id: str | None = None
    error: dict[str, Any]

    model_config = {"serialize_by_alias": True}


class AgentCard(BaseModel):
    name: str
    description: str
    version: str
    default_input_modes: list[str] = Field(alias="defaultInputModes")
    default_output_modes: list[str] = Field(alias="defaultOutputModes")
    capabilities: dict[str, Any]
    skills: list[dict[str, Any]]

    model_config = {"populate_by_name": True, "serialize_by_alias": True}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd agents/default && python -m pytest tests/test_models.py -v`
Expected: 5 passed

- [ ] **Step 5: 커밋**

```bash
git add agents/default/app/models/ agents/default/tests/test_models.py
git commit -m "feat(agent): add A2A JSON-RPC Pydantic models"
```

---

### Task 4: ChatAgent (LangChain)

**Files:**
- Create: `agents/default/app/agent/__init__.py`
- Create: `agents/default/app/agent/chat.py`
- Create: `agents/default/tests/test_chat.py`

- [ ] **Step 1: 테스트 작성**

`agents/default/app/agent/__init__.py` — 빈 파일

`agents/default/tests/test_chat.py`:

```python
from unittest.mock import AsyncMock, patch

import pytest

from app.agent.chat import ChatAgent


@pytest.fixture
def agent():
    with patch("app.agent.chat.ChatGoogleGenerativeAI") as mock_cls:
        mock_llm = AsyncMock()
        mock_llm.ainvoke.return_value = AsyncMock(content="안녕하세요!")
        mock_cls.return_value = mock_llm
        yield ChatAgent(model_name="test-model", api_key="test-key")


@pytest.mark.asyncio
async def test_invoke_returns_response(agent: ChatAgent):
    result = await agent.invoke("안녕", context_id="ctx-1")
    assert result == "안녕하세요!"


@pytest.mark.asyncio
async def test_invoke_creates_new_context_if_none(agent: ChatAgent):
    result = await agent.invoke("hello", context_id=None)
    assert result == "안녕하세요!"


@pytest.mark.asyncio
async def test_invoke_appends_to_history(agent: ChatAgent):
    await agent.invoke("첫 번째", context_id="ctx-2")
    await agent.invoke("두 번째", context_id="ctx-2")
    history = agent.get_history("ctx-2")
    assert len(history) == 4  # 2 human + 2 ai messages


@pytest.mark.asyncio
async def test_separate_contexts_have_separate_history(agent: ChatAgent):
    await agent.invoke("A", context_id="ctx-a")
    await agent.invoke("B", context_id="ctx-b")
    assert len(agent.get_history("ctx-a")) == 2
    assert len(agent.get_history("ctx-b")) == 2


@pytest.mark.asyncio
async def test_astream_yields_chunks(agent: ChatAgent):
    async def mock_stream(*args, **kwargs):
        for chunk in ["청크1", "청크2", "청크3"]:
            mock_chunk = AsyncMock()
            mock_chunk.content = chunk
            yield mock_chunk

    agent._llm.astream = mock_stream

    chunks = []
    async for chunk in agent.astream("스트림 테스트", context_id="ctx-s"):
        chunks.append(chunk)

    assert chunks == ["청크1", "청크2", "청크3"]
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd agents/default && python -m pytest tests/test_chat.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.agent'`

- [ ] **Step 3: ChatAgent 구현**

`agents/default/app/agent/chat.py`:

```python
from __future__ import annotations

import uuid
from collections.abc import AsyncGenerator

from langchain_core.messages import AIMessage, BaseMessage, HumanMessage
from langchain_google_genai import ChatGoogleGenerativeAI


class ChatAgent:
    def __init__(self, model_name: str, api_key: str) -> None:
        self._llm = ChatGoogleGenerativeAI(
            model=model_name,
            google_api_key=api_key,
        )
        self._histories: dict[str, list[BaseMessage]] = {}

    def _resolve_context_id(self, context_id: str | None) -> str:
        if context_id is None:
            return str(uuid.uuid4())
        return context_id

    def get_history(self, context_id: str) -> list[BaseMessage]:
        return self._histories.get(context_id, [])

    async def invoke(self, message: str, context_id: str | None = None) -> str:
        ctx = self._resolve_context_id(context_id)
        history = self._histories.setdefault(ctx, [])
        history.append(HumanMessage(content=message))

        response = await self._llm.ainvoke(history)
        history.append(AIMessage(content=response.content))
        return response.content

    async def astream(
        self, message: str, context_id: str | None = None
    ) -> AsyncGenerator[str, None]:
        ctx = self._resolve_context_id(context_id)
        history = self._histories.setdefault(ctx, [])
        history.append(HumanMessage(content=message))

        full_response = ""
        async for chunk in self._llm.astream(history):
            full_response += chunk.content
            yield chunk.content

        history.append(AIMessage(content=full_response))
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd agents/default && python -m pytest tests/test_chat.py -v`
Expected: 5 passed

- [ ] **Step 5: 커밋**

```bash
git add agents/default/app/agent/ agents/default/tests/test_chat.py
git commit -m "feat(agent): add ChatAgent with LangChain Google Generative AI"
```

---

### Task 5: AgentCard 라우트

**Files:**
- Create: `agents/default/app/routes/__init__.py`
- Create: `agents/default/app/routes/agent_card.py`
- Create: `agents/default/tests/test_agent_card_route.py`

- [ ] **Step 1: 테스트 작성**

`agents/default/app/routes/__init__.py` — 빈 파일

`agents/default/tests/test_agent_card_route.py`:

```python
from unittest.mock import patch

import pytest
from httpx import ASGITransport, AsyncClient

from app.main import create_app


@pytest.fixture
def app():
    with patch("app.main.ChatAgent"):
        return create_app()


@pytest.mark.asyncio
async def test_agent_card_returns_valid_json(app):
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        resp = await client.get("/.well-known/agent.json")

    assert resp.status_code == 200
    data = resp.json()
    assert data["name"] == "Bara Default Agent"
    assert data["version"] == "0.1.0"
    assert data["capabilities"]["streaming"] is True
    assert len(data["skills"]) == 1
    assert data["skills"][0]["id"] == "chat"
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd agents/default && python -m pytest tests/test_agent_card_route.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.main'`

- [ ] **Step 3: agent_card 라우트 구현**

`agents/default/app/routes/agent_card.py`:

```python
from fastapi import APIRouter

from app.models.a2a import AgentCard

router = APIRouter()

AGENT_CARD = AgentCard(
    name="Bara Default Agent",
    description="Bara 플랫폼 기본 범용 대화 에이전트",
    version="0.1.0",
    default_input_modes=["text"],
    default_output_modes=["text"],
    capabilities={"streaming": True, "pushNotifications": False},
    skills=[
        {
            "id": "chat",
            "name": "대화",
            "description": "자연어 대화를 수행합니다",
            "tags": ["chat", "general"],
            "examples": ["안녕하세요", "오늘 날씨 어때?"],
        }
    ],
)


@router.get("/.well-known/agent.json")
async def get_agent_card() -> dict:
    return AGENT_CARD.model_dump(by_alias=True)
```

- [ ] **Step 4: main.py 생성 (앱 팩토리)**

`agents/default/app/main.py`:

```python
from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import FastAPI

from app.agent.chat import ChatAgent
from app.config import Settings
from app.routes.agent_card import router as agent_card_router


@asynccontextmanager
async def lifespan(app: FastAPI):
    load_dotenv()
    settings = Settings()
    app.state.agent = ChatAgent(
        model_name=settings.model_name,
        api_key=settings.google_api_key,
    )
    yield


def create_app() -> FastAPI:
    app = FastAPI(title="Bara Default Agent", lifespan=lifespan)
    app.include_router(agent_card_router)
    return app


app = create_app()
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd agents/default && python -m pytest tests/test_agent_card_route.py -v`
Expected: 1 passed

- [ ] **Step 6: 커밋**

```bash
git add agents/default/app/main.py agents/default/app/routes/ agents/default/tests/test_agent_card_route.py
git commit -m "feat(agent): add AgentCard endpoint and FastAPI app factory"
```

---

### Task 6: 동기 태스크 라우트 (POST /)

**Files:**
- Create: `agents/default/app/routes/task.py`
- Create: `agents/default/tests/test_task_route.py`

- [ ] **Step 1: 테스트 작성**

`agents/default/tests/test_task_route.py`:

```python
from unittest.mock import AsyncMock, patch

import pytest
from httpx import ASGITransport, AsyncClient

from app.main import create_app


@pytest.fixture
def app():
    with patch("app.main.ChatAgent") as mock_cls:
        mock_agent = AsyncMock()
        mock_agent.invoke.return_value = "응답입니다"
        mock_cls.return_value = mock_agent

        test_app = create_app()
        test_app.state.agent = mock_agent
        yield test_app


def _make_request(text="안녕", task_id="task-1", context_id=None):
    params = {
        "id": task_id,
        "message": {"role": "user", "parts": [{"type": "text", "text": text}]},
    }
    if context_id:
        params["contextId"] = context_id
    return {
        "jsonrpc": "2.0",
        "id": "req-1",
        "method": "tasks/send",
        "params": params,
    }


@pytest.mark.asyncio
async def test_task_send_returns_completed_response(app):
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        resp = await client.post("/", json=_make_request())

    assert resp.status_code == 200
    data = resp.json()
    assert data["jsonrpc"] == "2.0"
    assert data["id"] == "req-1"
    assert data["result"]["id"] == "task-1"
    assert data["result"]["status"]["state"] == "completed"
    assert data["result"]["status"]["message"]["parts"][0]["text"] == "응답입니다"
    assert data["result"]["final"] is True


@pytest.mark.asyncio
async def test_task_send_with_context_id(app):
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        resp = await client.post(
            "/", json=_make_request(context_id="ctx-123")
        )

    data = resp.json()
    assert data["result"]["contextId"] == "ctx-123"
    app.state.agent.invoke.assert_called_once_with("안녕", context_id="ctx-123")


@pytest.mark.asyncio
async def test_invalid_method_returns_error(app):
    req = {
        "jsonrpc": "2.0",
        "id": "req-1",
        "method": "tasks/unknown",
        "params": {
            "id": "task-1",
            "message": {"role": "user", "parts": [{"type": "text", "text": "hi"}]},
        },
    }
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        resp = await client.post("/", json=req)

    assert resp.status_code == 200
    data = resp.json()
    assert data["error"]["code"] == -32601


@pytest.mark.asyncio
async def test_invalid_json_returns_parse_error(app):
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        resp = await client.post(
            "/", content="not json", headers={"content-type": "application/json"}
        )

    assert resp.status_code == 200
    data = resp.json()
    assert data["error"]["code"] == -32700
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd agents/default && python -m pytest tests/test_task_route.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'app.routes.task'`

- [ ] **Step 3: task 라우트 구현**

`agents/default/app/routes/task.py`:

```python
from __future__ import annotations

import logging

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from app.models.a2a import (
    A2AMessage,
    JsonRpcError,
    JsonRpcRequest,
    JsonRpcResponse,
    MessagePart,
    TaskResult,
    TaskStatus,
)

logger = logging.getLogger(__name__)

router = APIRouter()

SUPPORTED_METHODS = {"tasks/send", "tasks/sendSubscribe"}


def _error_response(req_id: str | None, code: int, message: str) -> JSONResponse:
    return JSONResponse(
        JsonRpcError(id=req_id, error={"code": code, "message": message}).model_dump(
            by_alias=True
        )
    )


@router.post("/")
async def handle_task(request: Request) -> JSONResponse:
    try:
        body = await request.json()
    except Exception:
        return _error_response(None, -32700, "Parse error")

    try:
        rpc_request = JsonRpcRequest.model_validate(body)
    except Exception:
        return _error_response(
            body.get("id"), -32600, "Invalid request"
        )

    if rpc_request.method not in SUPPORTED_METHODS:
        return _error_response(rpc_request.id, -32601, "Method not found")

    agent = request.app.state.agent
    text = rpc_request.params.message.parts[0].text
    context_id = rpc_request.params.context_id

    try:
        response_text = await agent.invoke(text, context_id=context_id)
    except Exception:
        logger.exception("LLM invocation failed")
        return _error_response(rpc_request.id, -32603, "Internal error")

    result = JsonRpcResponse(
        id=rpc_request.id,
        result=TaskResult(
            id=rpc_request.params.id,
            context_id=context_id,
            status=TaskStatus(
                state="completed",
                message=A2AMessage(
                    role="agent",
                    parts=[MessagePart(text=response_text)],
                ),
            ),
            final=True,
        ),
    )
    return JSONResponse(result.model_dump(by_alias=True))
```

- [ ] **Step 4: main.py에 task 라우터 등록**

`agents/default/app/main.py`에 task 라우터 import 및 등록 추가:

```python
from contextlib import asynccontextmanager

from dotenv import load_dotenv
from fastapi import FastAPI

from app.agent.chat import ChatAgent
from app.config import Settings
from app.routes.agent_card import router as agent_card_router
from app.routes.task import router as task_router


@asynccontextmanager
async def lifespan(app: FastAPI):
    load_dotenv()
    settings = Settings()
    app.state.agent = ChatAgent(
        model_name=settings.model_name,
        api_key=settings.google_api_key,
    )
    yield


def create_app() -> FastAPI:
    app = FastAPI(title="Bara Default Agent", lifespan=lifespan)
    app.include_router(agent_card_router)
    app.include_router(task_router)
    return app


app = create_app()
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd agents/default && python -m pytest tests/test_task_route.py -v`
Expected: 4 passed

- [ ] **Step 6: 커밋**

```bash
git add agents/default/app/routes/task.py agents/default/app/main.py agents/default/tests/test_task_route.py
git commit -m "feat(agent): add synchronous task endpoint (POST /)"
```

---

### Task 7: SSE 스트리밍 라우트 (POST /stream)

**Files:**
- Modify: `agents/default/app/routes/task.py`
- Create: `agents/default/tests/test_stream_route.py`

- [ ] **Step 1: 테스트 작성**

`agents/default/tests/test_stream_route.py`:

```python
from unittest.mock import AsyncMock, patch

import pytest
from httpx import ASGITransport, AsyncClient

from app.main import create_app


@pytest.fixture
def app():
    with patch("app.main.ChatAgent") as mock_cls:
        mock_agent = AsyncMock()

        async def mock_astream(message, context_id=None):
            for chunk in ["청크1", "청크2", "청크3"]:
                yield chunk

        mock_agent.astream = mock_astream
        mock_cls.return_value = mock_agent

        test_app = create_app()
        test_app.state.agent = mock_agent
        yield test_app


def _make_request(text="안녕", task_id="task-1", context_id=None):
    params = {
        "id": task_id,
        "message": {"role": "user", "parts": [{"type": "text", "text": text}]},
    }
    if context_id:
        params["contextId"] = context_id
    return {
        "jsonrpc": "2.0",
        "id": "req-1",
        "method": "tasks/sendSubscribe",
        "params": params,
    }


@pytest.mark.asyncio
async def test_stream_returns_sse_events(app):
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        resp = await client.post("/stream", json=_make_request())

    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers["content-type"]

    lines = resp.text.strip().split("\n")
    events = [l for l in lines if l.startswith("data:")]
    assert len(events) == 3  # 3 chunks

    import json

    # 마지막 이벤트는 final=True
    last_event = json.loads(events[-1].removeprefix("data:").strip())
    assert last_event["result"]["final"] is True
    assert last_event["result"]["status"]["state"] == "completed"

    # 첫 번째 이벤트는 final=False
    first_event = json.loads(events[0].removeprefix("data:").strip())
    assert first_event["result"]["final"] is False
    assert first_event["result"]["status"]["state"] == "working"


@pytest.mark.asyncio
async def test_stream_invalid_method_returns_error(app):
    req = _make_request()
    req["method"] = "tasks/unknown"
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        resp = await client.post("/stream", json=req)

    assert resp.status_code == 200
    data = resp.json()
    assert data["error"]["code"] == -32601
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd agents/default && python -m pytest tests/test_stream_route.py -v`
Expected: FAIL

- [ ] **Step 3: 스트리밍 엔드포인트 구현**

`agents/default/app/routes/task.py`에 `/stream` 엔드포인트 추가:

```python
from __future__ import annotations

import json
import logging
from collections.abc import AsyncGenerator

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from sse_starlette.sse import EventSourceResponse

from app.models.a2a import (
    A2AMessage,
    JsonRpcError,
    JsonRpcRequest,
    JsonRpcResponse,
    MessagePart,
    TaskResult,
    TaskStatus,
)

logger = logging.getLogger(__name__)

router = APIRouter()

SUPPORTED_METHODS = {"tasks/send", "tasks/sendSubscribe"}


def _error_response(req_id: str | None, code: int, message: str) -> JSONResponse:
    return JSONResponse(
        JsonRpcError(id=req_id, error={"code": code, "message": message}).model_dump(
            by_alias=True
        )
    )


def _validate_request(body: dict) -> JsonRpcRequest | JSONResponse:
    try:
        rpc_request = JsonRpcRequest.model_validate(body)
    except Exception:
        return _error_response(body.get("id"), -32600, "Invalid request")

    if rpc_request.method not in SUPPORTED_METHODS:
        return _error_response(rpc_request.id, -32601, "Method not found")

    return rpc_request


@router.post("/")
async def handle_task(request: Request) -> JSONResponse:
    try:
        body = await request.json()
    except Exception:
        return _error_response(None, -32700, "Parse error")

    result = _validate_request(body)
    if isinstance(result, JSONResponse):
        return result
    rpc_request = result

    agent = request.app.state.agent
    text = rpc_request.params.message.parts[0].text
    context_id = rpc_request.params.context_id

    try:
        response_text = await agent.invoke(text, context_id=context_id)
    except Exception:
        logger.exception("LLM invocation failed")
        return _error_response(rpc_request.id, -32603, "Internal error")

    response = JsonRpcResponse(
        id=rpc_request.id,
        result=TaskResult(
            id=rpc_request.params.id,
            context_id=context_id,
            status=TaskStatus(
                state="completed",
                message=A2AMessage(
                    role="agent",
                    parts=[MessagePart(text=response_text)],
                ),
            ),
            final=True,
        ),
    )
    return JSONResponse(response.model_dump(by_alias=True))


@router.post("/stream")
async def handle_task_stream(request: Request):
    try:
        body = await request.json()
    except Exception:
        return _error_response(None, -32700, "Parse error")

    result = _validate_request(body)
    if isinstance(result, JSONResponse):
        return result
    rpc_request = result

    agent = request.app.state.agent
    text = rpc_request.params.message.parts[0].text
    context_id = rpc_request.params.context_id

    async def event_generator() -> AsyncGenerator[dict, None]:
        accumulated = ""
        is_first = True
        last_chunk = ""

        async for chunk in agent.astream(text, context_id=context_id):
            if not is_first:
                event_data = JsonRpcResponse(
                    id=rpc_request.id,
                    result=TaskResult(
                        id=rpc_request.params.id,
                        context_id=context_id,
                        status=TaskStatus(
                            state="working",
                            message=A2AMessage(
                                role="agent",
                                parts=[MessagePart(text=accumulated)],
                            ),
                        ),
                        final=False,
                    ),
                )
                yield {"event": "message", "data": event_data.model_dump_json(by_alias=True)}
            accumulated += chunk
            last_chunk = chunk
            is_first = False

        final_data = JsonRpcResponse(
            id=rpc_request.id,
            result=TaskResult(
                id=rpc_request.params.id,
                context_id=context_id,
                status=TaskStatus(
                    state="completed",
                    message=A2AMessage(
                        role="agent",
                        parts=[MessagePart(text=accumulated)],
                    ),
                ),
                final=True,
            ),
        )
        yield {"event": "message", "data": final_data.model_dump_json(by_alias=True)}

    return EventSourceResponse(event_generator())
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd agents/default && python -m pytest tests/test_stream_route.py -v`
Expected: 2 passed

- [ ] **Step 5: 전체 테스트 통과 확인**

Run: `cd agents/default && python -m pytest -v`
Expected: 전체 테스트 통과 (기존 테스트 포함)

- [ ] **Step 6: 커밋**

```bash
git add agents/default/app/routes/task.py agents/default/tests/test_stream_route.py
git commit -m "feat(agent): add SSE streaming endpoint (POST /stream)"
```

---

### Task 8: 수동 통합 테스트 및 마무리

**Files:**
- Modify: `agents/default/.env.example` (이미 생성됨, 변경 없음)

- [ ] **Step 1: .env 파일 생성 (로컬 테스트용)**

```bash
cd agents/default
cp .env.example .env
# .env에 실제 GOOGLE_API_KEY 입력
```

- [ ] **Step 2: 서버 실행**

Run: `cd agents/default && uvicorn app.main:app --port 8090 --reload`
Expected: `INFO: Uvicorn running on http://127.0.0.1:8090`

- [ ] **Step 3: AgentCard 확인**

Run: `curl -s http://localhost:8090/.well-known/agent.json | python -m json.tool`
Expected: AgentCard JSON 출력 (name: "Bara Default Agent")

- [ ] **Step 4: 동기 태스크 테스트**

Run:
```bash
curl -s -X POST http://localhost:8090/ \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "test-1",
    "method": "tasks/send",
    "params": {
      "id": "task-1",
      "message": {"role": "user", "parts": [{"type": "text", "text": "안녕하세요"}]}
    }
  }' | python -m json.tool
```
Expected: `"state": "completed"`, agent 응답 텍스트 포함

- [ ] **Step 5: SSE 스트리밍 테스트**

Run:
```bash
curl -s -N -X POST http://localhost:8090/stream \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc": "2.0",
    "id": "test-2",
    "method": "tasks/sendSubscribe",
    "params": {
      "id": "task-2",
      "message": {"role": "user", "parts": [{"type": "text", "text": "한국에 대해 간단히 알려줘"}]}
    }
  }'
```
Expected: `event: message` + `data: {...}` 형태의 SSE 이벤트 스트림, 마지막 이벤트에 `"final": true`

- [ ] **Step 6: 전체 자동 테스트 최종 확인**

Run: `cd agents/default && python -m pytest -v`
Expected: 전체 테스트 통과

- [ ] **Step 7: .gitignore 추가 및 최종 커밋**

`agents/default/.gitignore`:
```
.env
__pycache__/
*.egg-info/
.pytest_cache/
```

```bash
git add agents/default/.gitignore
git commit -m "chore(agent): add .gitignore for default agent"
```

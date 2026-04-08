# Bara Default Agent 설계

## 개요

Bara 플랫폼의 공식 기본 Agent. Gemini Flash Lite 기반 범용 대화 Agent로, FastAPI HTTP 엔드포인트를 통해 A2A 메시지 포맷을 처리한다. Kafka 연동은 후속 단계에서 전환.

## 위치

`agents/default/` — 모노레포 내 독립 Python 프로젝트 (Gradle 대상 아님)

## 기술 스택

- Python 3.12+
- FastAPI + Uvicorn
- LangChain + langchain-google-genai (`ChatGoogleGenerativeAI`)
- Pydantic v2 (A2A 메시지 모델)
- python-dotenv (환경변수)

## 엔드포인트

A2A 스펙 기반 JSON-RPC 2.0:

| Method | Path | 설명 |
|--------|------|------|
| POST | `/` | 태스크 처리 (동기 응답) |
| POST | `/stream` | 태스크 처리 (SSE 스트리밍) |
| GET | `/.well-known/agent.json` | AgentCard 반환 |

## 메시지 포맷

### 요청 (A2A `tasks/send`)

```json
{
  "jsonrpc": "2.0",
  "id": "req-1",
  "method": "tasks/send",
  "params": {
    "id": "task-123",
    "message": {
      "role": "user",
      "parts": [{"type": "text", "text": "안녕하세요"}]
    },
    "contextId": "ctx-abc"
  }
}
```

### 동기 응답

```json
{
  "jsonrpc": "2.0",
  "id": "req-1",
  "result": {
    "id": "task-123",
    "contextId": "ctx-abc",
    "status": {
      "state": "completed",
      "message": {
        "role": "agent",
        "parts": [{"type": "text", "text": "안녕하세요!"}]
      }
    },
    "final": true
  }
}
```

### SSE 스트리밍 응답

```
event: message
data: {"jsonrpc":"2.0","id":"req-1","result":{"id":"task-123","contextId":"ctx-abc","status":{"state":"working","message":{"role":"agent","parts":[{"type":"text","text":"처리"}]}},"final":false}}

event: message
data: {"jsonrpc":"2.0","id":"req-1","result":{"id":"task-123","contextId":"ctx-abc","status":{"state":"completed","message":{"role":"agent","parts":[{"type":"text","text":"처리 중입니다..."}]}},"final":true}}
```

스트리밍 시 LangChain `astream`으로 토큰 단위 청크를 받아 `working` 상태로 전송하고, 마지막 청크에서 `completed` + `final: true`로 종료한다.

## 프로젝트 구조

```
agents/default/
├── pyproject.toml
├── .env.example
├── README.md
└── app/
    ├── __init__.py
    ├── main.py            # FastAPI app, lifespan
    ├── config.py           # 환경변수 Settings (pydantic-settings)
    ├── models/
    │   ├── __init__.py
    │   └── a2a.py          # A2A JSON-RPC Pydantic 모델
    ├── routes/
    │   ├── __init__.py
    │   ├── task.py          # POST /, POST /stream
    │   └── agent_card.py    # GET /.well-known/agent.json
    └── agent/
        ├── __init__.py
        └── chat.py          # LangChain ChatAgent (invoke, astream)
```

## 핵심 컴포넌트

### ChatAgent (`app/agent/chat.py`)

- `ChatGoogleGenerativeAI(model="gemini-2.5-flash-lite-preview-06-17")` 사용
- 대화 이력: 인메모리 `dict[str, list[BaseMessage]]` (key: `context_id`)
- `context_id`가 없으면 새 UUID 생성, 있으면 기존 이력에 이어서 대화
- `invoke(message, context_id)` → 동기 응답
- `astream(message, context_id)` → AsyncGenerator로 토큰 스트리밍
- 재시작 시 이력 초기화 (영속화는 후속 단계)

### A2A 모델 (`app/models/a2a.py`)

Pydantic v2 모델로 JSON-RPC 2.0 + A2A 메시지 구조를 정의:

- `JsonRpcRequest` — 요청 envelope (jsonrpc, id, method, params)
- `TaskSendParams` — params 내부 (id, message, contextId)
- `A2AMessage` — role + parts
- `MessagePart` — type + text (현재 text만 지원)
- `TaskResult` — 응답 result (id, contextId, status, final)
- `TaskStatus` — state + message
- `JsonRpcResponse` — 응답 envelope (jsonrpc, id, result)
- `JsonRpcError` — 에러 응답 (jsonrpc, id, error with code/message)
- `AgentCard` — A2A AgentCard 스키마

### 에러 처리

JSON-RPC 2.0 에러 코드 사용:

| 코드 | 이름 | 상황 |
|------|------|------|
| -32700 | ParseError | JSON 파싱 실패 |
| -32600 | InvalidRequest | 잘못된 요청 형식 |
| -32601 | MethodNotFound | 지원하지 않는 method |
| -32603 | InternalError | LLM 호출 실패 등 내부 오류 |

## AgentCard

```json
{
  "name": "Bara Default Agent",
  "description": "Bara 플랫폼 기본 범용 대화 에이전트",
  "version": "0.1.0",
  "defaultInputModes": ["text"],
  "defaultOutputModes": ["text"],
  "capabilities": {
    "streaming": true,
    "pushNotifications": false
  },
  "skills": [
    {
      "id": "chat",
      "name": "대화",
      "description": "자연어 대화를 수행합니다",
      "tags": ["chat", "general"],
      "examples": ["안녕하세요", "오늘 날씨 어때?"]
    }
  ]
}
```

## 환경변수

```env
GOOGLE_API_KEY=...                              # Gemini API Key
MODEL_NAME=gemini-2.5-flash-lite-preview-06-17  # LLM 모델
PORT=8090                                        # 서버 포트
```

## 실행

```bash
cd agents/default
pip install -e .     # 또는 uv sync
uvicorn app.main:app --port 8090 --reload
```

## 후속 확장 포인트

- Kafka 통신 레이어 추가 (SDK 완성 시 HTTP → Kafka 전환)
- 도구(Tools) 추가 (웹 검색, 코드 실행 등)
- Redis 기반 대화 이력 영속화
- Docker 이미지 + K8s 배포
- MCP 기반 동적 도구 확장

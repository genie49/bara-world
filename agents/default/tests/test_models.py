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

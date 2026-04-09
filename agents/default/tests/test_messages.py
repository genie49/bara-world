import json

from app.models.messages import (
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

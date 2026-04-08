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

from unittest.mock import AsyncMock, patch

import pytest

from app.kafka.producer import ResultProducer
from app.models.messages import (
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
async def test_start_and_stop(producer: ResultProducer):
    await producer.start()
    producer._producer.start.assert_called_once()
    await producer.stop()
    producer._producer.stop.assert_called_once()

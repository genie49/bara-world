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

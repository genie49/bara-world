from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.agent.chat import ChatAgent


@pytest.fixture
def agent():
    with patch("app.agent.chat.create_agent") as mock_create:
        mock_graph = AsyncMock()
        mock_graph.ainvoke.return_value = {
            "messages": [MagicMock(content="안녕하세요!")]
        }
        mock_create.return_value = mock_graph
        yield ChatAgent(api_key="test-key")


@pytest.mark.asyncio
async def test_invoke_returns_response(agent: ChatAgent):
    result = await agent.invoke("안녕", context_id="ctx-1")
    assert result == "안녕하세요!"


@pytest.mark.asyncio
async def test_invoke_creates_new_context_if_none(agent: ChatAgent):
    result = await agent.invoke("hello", context_id=None)
    assert result == "안녕하세요!"


@pytest.mark.asyncio
async def test_invoke_passes_thread_id(agent: ChatAgent):
    await agent.invoke("test", context_id="ctx-42")
    call_args = agent._agent.ainvoke.call_args
    config = call_args[1].get("config") or call_args[0][1]
    assert config["configurable"]["thread_id"] == "ctx-42"


@pytest.mark.asyncio
async def test_astream_yields_chunks(agent: ChatAgent):
    async def mock_events(*args, **kwargs):
        for text in ["청크1", "청크2", "청크3"]:
            yield {
                "event": "on_chat_model_stream",
                "data": {"chunk": MagicMock(content=text)},
            }

    agent._agent.astream_events = mock_events

    chunks = []
    async for chunk in agent.astream("스트림 테스트", context_id="ctx-s"):
        chunks.append(chunk)

    assert chunks == ["청크1", "청크2", "청크3"]


@pytest.mark.asyncio
async def test_astream_skips_empty_content(agent: ChatAgent):
    async def mock_events(*args, **kwargs):
        yield {
            "event": "on_chat_model_stream",
            "data": {"chunk": MagicMock(content="")},
        }
        yield {
            "event": "on_chat_model_stream",
            "data": {"chunk": MagicMock(content="실제 내용")},
        }
        yield {
            "event": "on_other_event",
            "data": {},
        }

    agent._agent.astream_events = mock_events

    chunks = []
    async for chunk in agent.astream("test", context_id="ctx-e"):
        chunks.append(chunk)

    assert chunks == ["실제 내용"]

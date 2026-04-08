from unittest.mock import AsyncMock, patch

import pytest

from app.agent.chat import ChatAgent


@pytest.fixture
def agent():
    with patch("app.agent.chat.ChatGoogleGenerativeAI") as mock_cls:
        mock_llm = AsyncMock()
        mock_llm.ainvoke.return_value = AsyncMock(content="안녕하세요!")
        mock_cls.return_value = mock_llm
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

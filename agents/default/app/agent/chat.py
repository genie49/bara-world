from __future__ import annotations

import os
import uuid
from collections.abc import AsyncGenerator

from langchain.agents import create_agent
from langgraph.checkpoint.memory import MemorySaver

MODEL_NAME = "gemini-3.1-flash-lite-preview"


class ChatAgent:
    def __init__(self, api_key: str) -> None:
        os.environ.setdefault("GOOGLE_API_KEY", api_key)
        self._checkpointer = MemorySaver()
        self._agent = create_agent(
            model=f"google_genai:{MODEL_NAME}",
            tools=[],
            checkpointer=self._checkpointer,
        )

    def _make_config(self, context_id: str | None) -> dict:
        thread_id = context_id or str(uuid.uuid4())
        return {"configurable": {"thread_id": thread_id}, "context_id": thread_id}

    async def invoke(self, message: str, context_id: str | None = None) -> str:
        config = self._make_config(context_id)
        result = await self._agent.ainvoke(
            {"messages": [{"role": "user", "content": message}]},
            config=config,
        )
        return result["messages"][-1].content

    async def astream(
        self, message: str, context_id: str | None = None
    ) -> AsyncGenerator[str, None]:
        config = self._make_config(context_id)
        async for event in self._agent.astream_events(
            {"messages": [{"role": "user", "content": message}]},
            config=config,
            version="v2",
        ):
            if event["event"] == "on_chat_model_stream":
                chunk = event["data"]["chunk"]
                if chunk.content:
                    yield chunk.content

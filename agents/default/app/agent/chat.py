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

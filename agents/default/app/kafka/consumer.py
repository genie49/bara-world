from __future__ import annotations

import asyncio
import json
import logging
import uuid
from datetime import datetime, timezone

from aiokafka import AIOKafkaConsumer

from app.agent.chat import ChatAgent
from app.kafka.producer import ResultProducer
from app.logging import WideEvent
from app.models.messages import (
    Message,
    Part,
    TaskMessage,
    TaskResult,
    TaskStatus,
)

logger = logging.getLogger(__name__)


class TaskConsumer:
    def __init__(
        self,
        agent_id: str,
        bootstrap_servers: str,
        agent: ChatAgent,
        producer: ResultProducer,
    ) -> None:
        self._agent_id = agent_id
        self._bootstrap_servers = bootstrap_servers
        self._agent = agent
        self._producer = producer
        self._consumer: AIOKafkaConsumer | None = None
        self._task: asyncio.Task | None = None

    async def start(self) -> None:
        topic = f"tasks.{self._agent_id}"
        self._consumer = AIOKafkaConsumer(
            topic,
            bootstrap_servers=self._bootstrap_servers,
            group_id=f"agent-{self._agent_id}",
        )
        await self._consumer.start()
        self._task = asyncio.create_task(self._consume_loop())
        logger.info("TaskConsumer started on topic=%s", topic)

    async def stop(self) -> None:
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None
        if self._consumer:
            await self._consumer.stop()
        logger.info("TaskConsumer stopped")

    async def _consume_loop(self) -> None:
        async for msg in self._consumer:
            await self._process_message(msg.value)

    async def _process_message(self, raw: bytes) -> None:
        try:
            data = json.loads(raw)
            task_msg = TaskMessage.model_validate(data)
        except Exception:
            logger.warning("Failed to deserialize task message, skipping")
            return

        WideEvent.put("task_id", task_msg.task_id)
        WideEvent.put("context_id", task_msg.context_id)
        WideEvent.put("user_id", task_msg.user_id)
        WideEvent.put("request_id", task_msg.request_id)

        text = task_msg.message.parts[0].text if task_msg.message.parts else ""

        try:
            response_text = await self._agent.invoke(
                text, context_id=task_msg.context_id
            )
            status = TaskStatus(
                state="completed",
                message=Message(
                    message_id=str(uuid.uuid4()),
                    role="agent",
                    parts=[Part(text=response_text)],
                ),
                timestamp=datetime.now(timezone.utc).isoformat(),
            )
            WideEvent.put("outcome", "success")
            WideEvent.message("task completed")
        except Exception:
            logger.exception("LLM invocation failed for task=%s", task_msg.task_id)
            status = TaskStatus(
                state="failed",
                message=Message(
                    message_id=str(uuid.uuid4()),
                    role="agent",
                    parts=[Part(text="Internal error: LLM invocation failed")],
                ),
                timestamp=datetime.now(timezone.utc).isoformat(),
            )
            WideEvent.put("outcome", "error")
            WideEvent.message("task failed")

        result = TaskResult(
            task_id=task_msg.task_id,
            context_id=task_msg.context_id,
            user_id=task_msg.user_id,
            request_id=task_msg.request_id,
            agent_id=self._agent_id,
            status=status,
            final=True,
        )

        try:
            await self._producer.send_result(task_msg.result_topic, result)
        except Exception:
            logger.exception("Failed to send result for task=%s", task_msg.task_id)

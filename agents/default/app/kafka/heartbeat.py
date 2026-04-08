from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone

from app.kafka.producer import ResultProducer
from app.models.messages import HeartbeatMessage

logger = logging.getLogger(__name__)

HEARTBEAT_INTERVAL = 20


class HeartbeatLoop:
    def __init__(
        self,
        agent_id: str,
        producer: ResultProducer,
        interval: float = HEARTBEAT_INTERVAL,
    ) -> None:
        self._agent_id = agent_id
        self._producer = producer
        self._interval = interval
        self._task: asyncio.Task | None = None

    async def start(self) -> None:
        self._task = asyncio.create_task(self._loop())
        logger.info("Heartbeat loop started (interval=%ss)", self._interval)

    async def stop(self) -> None:
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None
        logger.info("Heartbeat loop stopped")

    async def _loop(self) -> None:
        while True:
            try:
                msg = HeartbeatMessage(
                    agent_id=self._agent_id,
                    timestamp=datetime.now(timezone.utc).isoformat(),
                )
                await self._producer.send_heartbeat(msg)
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.exception("Failed to send heartbeat")
            await asyncio.sleep(self._interval)

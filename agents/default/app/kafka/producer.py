from __future__ import annotations

import logging

from aiokafka import AIOKafkaProducer

from app.models.messages import HeartbeatMessage, TaskResult

logger = logging.getLogger(__name__)


class ResultProducer:
    def __init__(self, bootstrap_servers: str) -> None:
        self._producer = AIOKafkaProducer(
            bootstrap_servers=bootstrap_servers,
        )

    async def start(self) -> None:
        await self._producer.start()
        logger.info("Kafka producer started")

    async def stop(self) -> None:
        await self._producer.stop()
        logger.info("Kafka producer stopped")

    async def send_result(self, topic: str, result: TaskResult) -> None:
        value = result.model_dump_json(exclude_none=True).encode()
        await self._producer.send_and_wait(topic, value=value)

    async def send_heartbeat(self, msg: HeartbeatMessage) -> None:
        value = msg.model_dump_json().encode()
        await self._producer.send_and_wait("heartbeat", value=value)

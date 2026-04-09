from __future__ import annotations

import asyncio
import logging

import httpx

from app.config import Settings

logger = logging.getLogger(__name__)


class RegistryError(Exception):
    pass


class RegistryClient:
    def __init__(
        self,
        settings: Settings,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._agent_name = settings.agent_name
        self._interval = settings.heartbeat_interval
        self._client = httpx.AsyncClient(
            base_url=settings.api_service_url,
            headers={"Authorization": f"Bearer {settings.provider_api_key}"},
            transport=transport,
        )

    async def register(self) -> None:
        response = await self._client.post(f"/agents/{self._agent_name}/registry")
        if response.status_code != 200:
            raise RegistryError(
                f"Registry failed: status={response.status_code} body={response.text}"
            )
        logger.info("Agent registered: %s", self._agent_name)

    async def heartbeat_loop(self) -> None:
        while True:
            try:
                response = await self._client.post(
                    f"/agents/{self._agent_name}/heartbeat"
                )
                if response.status_code == 404:
                    logger.warning(
                        "Agent not registered, re-registering: %s", self._agent_name
                    )
                    try:
                        await self.register()
                    except RegistryError as e:
                        logger.error("Re-registration failed: %s", e)
                elif response.status_code != 200:
                    logger.warning("Heartbeat failed: status=%d", response.status_code)
            except httpx.HTTPError as e:
                logger.warning("Heartbeat error: %s", e)
            await asyncio.sleep(self._interval)

    async def close(self) -> None:
        await self._client.aclose()

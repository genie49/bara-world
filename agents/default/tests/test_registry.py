import asyncio

import httpx
import pytest

from app.config import Settings
from app.registry import RegistryClient


@pytest.fixture
def settings():
    return Settings(
        agent_name="my-agent",
        api_service_url="http://test",
        provider_api_key="test-key",
        heartbeat_interval=0.1,
    )


@pytest.mark.asyncio
async def test_register_success(settings):
    transport = httpx.MockTransport(lambda req: httpx.Response(200))
    client = RegistryClient(settings, transport=transport)
    await client.register()
    await client.close()


@pytest.mark.asyncio
async def test_register_failure_raises(settings):
    transport = httpx.MockTransport(lambda req: httpx.Response(404))
    client = RegistryClient(settings, transport=transport)
    with pytest.raises(Exception):
        await client.register()
    await client.close()


@pytest.mark.asyncio
async def test_heartbeat_loop_sends_requests(settings):
    call_count = 0

    def handler(req: httpx.Request) -> httpx.Response:
        nonlocal call_count
        call_count += 1
        return httpx.Response(200)

    transport = httpx.MockTransport(handler)
    client = RegistryClient(settings, transport=transport)
    task = asyncio.create_task(client.heartbeat_loop())
    await asyncio.sleep(0.35)
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass
    await client.close()
    assert call_count >= 2


@pytest.mark.asyncio
async def test_heartbeat_failure_does_not_raise(settings):
    transport = httpx.MockTransport(lambda req: httpx.Response(500))
    client = RegistryClient(settings, transport=transport)
    task = asyncio.create_task(client.heartbeat_loop())
    await asyncio.sleep(0.25)
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass
    await client.close()


@pytest.mark.asyncio
async def test_heartbeat_404_triggers_reregister(settings):
    call_count = {"heartbeat": 0, "registry": 0}

    def handler(req: httpx.Request) -> httpx.Response:
        if "/heartbeat" in req.url.path:
            call_count["heartbeat"] += 1
            return httpx.Response(404)
        if "/registry" in req.url.path:
            call_count["registry"] += 1
            return httpx.Response(200)
        return httpx.Response(404)

    transport = httpx.MockTransport(handler)
    client = RegistryClient(settings, transport=transport)
    task = asyncio.create_task(client.heartbeat_loop())
    await asyncio.sleep(0.35)
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass
    await client.close()
    assert call_count["heartbeat"] >= 2
    assert call_count["registry"] >= 2


@pytest.mark.asyncio
async def test_close_cleans_up(settings):
    transport = httpx.MockTransport(lambda req: httpx.Response(200))
    client = RegistryClient(settings, transport=transport)
    await client.close()

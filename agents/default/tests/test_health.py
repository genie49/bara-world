from unittest.mock import patch

import pytest
from httpx import ASGITransport, AsyncClient

from app.main import create_app


@pytest.fixture
def app():
    with patch("app.main.ChatAgent"):
        return create_app()


@pytest.mark.asyncio
async def test_readiness(app):
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        resp = await client.get("/health/readiness")
    assert resp.status_code == 200
    assert resp.json()["status"] == "UP"


@pytest.mark.asyncio
async def test_liveness(app):
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        resp = await client.get("/health/liveness")
    assert resp.status_code == 200
    assert resp.json()["status"] == "UP"

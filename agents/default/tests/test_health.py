from unittest.mock import AsyncMock, patch

import pytest
from httpx import ASGITransport, AsyncClient

from app.main import create_app


@pytest.fixture
def app():
    with patch("app.main.ChatAgent"), \
         patch("app.main.ResultProducer") as mock_prod_cls, \
         patch("app.main.TaskConsumer") as mock_cons_cls, \
         patch("app.main.HeartbeatLoop") as mock_hb_cls:
        mock_prod_cls.return_value = AsyncMock()
        mock_cons_cls.return_value = AsyncMock()
        mock_hb_cls.return_value = AsyncMock()
        yield create_app()


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

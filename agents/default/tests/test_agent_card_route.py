from unittest.mock import patch

import pytest
from httpx import ASGITransport, AsyncClient

from app.main import create_app


@pytest.fixture
def app():
    with patch("app.main.ChatAgent"):
        return create_app()


@pytest.mark.asyncio
async def test_agent_card_returns_valid_json(app):
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        resp = await client.get("/.well-known/agent.json")

    assert resp.status_code == 200
    data = resp.json()
    assert data["name"] == "Bara Default Agent"
    assert data["version"] == "0.1.0"
    assert data["capabilities"]["streaming"] is True
    assert len(data["skills"]) == 1
    assert data["skills"][0]["id"] == "chat"

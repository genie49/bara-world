from unittest.mock import AsyncMock, patch

import pytest
from httpx import ASGITransport, AsyncClient

from app.main import create_app


@pytest.fixture
def app():
    with patch("app.main.ChatAgent") as mock_cls:
        mock_agent = AsyncMock()
        mock_agent.invoke.return_value = "응답입니다"
        mock_cls.return_value = mock_agent

        test_app = create_app()
        test_app.state.agent = mock_agent
        yield test_app


def _make_request(text="안녕", task_id="task-1", context_id=None):
    params = {
        "id": task_id,
        "message": {"role": "user", "parts": [{"type": "text", "text": text}]},
    }
    if context_id:
        params["contextId"] = context_id
    return {
        "jsonrpc": "2.0",
        "id": "req-1",
        "method": "tasks/send",
        "params": params,
    }


@pytest.mark.asyncio
async def test_task_send_returns_completed_response(app):
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        resp = await client.post("/", json=_make_request())

    assert resp.status_code == 200
    data = resp.json()
    assert data["jsonrpc"] == "2.0"
    assert data["id"] == "req-1"
    assert data["result"]["id"] == "task-1"
    assert data["result"]["status"]["state"] == "completed"
    assert data["result"]["status"]["message"]["parts"][0]["text"] == "응답입니다"
    assert data["result"]["final"] is True


@pytest.mark.asyncio
async def test_task_send_with_context_id(app):
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        resp = await client.post(
            "/", json=_make_request(context_id="ctx-123")
        )

    data = resp.json()
    assert data["result"]["contextId"] == "ctx-123"
    app.state.agent.invoke.assert_called_once_with("안녕", context_id="ctx-123")


@pytest.mark.asyncio
async def test_invalid_method_returns_error(app):
    req = {
        "jsonrpc": "2.0",
        "id": "req-1",
        "method": "tasks/unknown",
        "params": {
            "id": "task-1",
            "message": {"role": "user", "parts": [{"type": "text", "text": "hi"}]},
        },
    }
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        resp = await client.post("/", json=req)

    assert resp.status_code == 200
    data = resp.json()
    assert data["error"]["code"] == -32601


@pytest.mark.asyncio
async def test_invalid_json_returns_parse_error(app):
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        resp = await client.post(
            "/", content="not json", headers={"content-type": "application/json"}
        )

    assert resp.status_code == 200
    data = resp.json()
    assert data["error"]["code"] == -32700

from unittest.mock import AsyncMock, patch

import pytest
from httpx import ASGITransport, AsyncClient

from app.main import create_app


@pytest.fixture
def app():
    with patch("app.main.ChatAgent") as mock_cls:
        mock_agent = AsyncMock()

        async def mock_astream(message, context_id=None):
            for chunk in ["청크1", "청크2", "청크3"]:
                yield chunk

        mock_agent.astream = mock_astream
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
        "method": "tasks/sendSubscribe",
        "params": params,
    }


@pytest.mark.asyncio
async def test_stream_returns_sse_events(app):
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        resp = await client.post("/stream", json=_make_request())

    assert resp.status_code == 200
    assert "text/event-stream" in resp.headers["content-type"]

    lines = resp.text.strip().split("\n")
    events = [l for l in lines if l.startswith("data:")]
    assert len(events) == 3  # 3 chunks

    import json

    # 마지막 이벤트는 final=True
    last_event = json.loads(events[-1].removeprefix("data:").strip())
    assert last_event["result"]["final"] is True
    assert last_event["result"]["status"]["state"] == "completed"

    # 첫 번째 이벤트는 final=False
    first_event = json.loads(events[0].removeprefix("data:").strip())
    assert first_event["result"]["final"] is False
    assert first_event["result"]["status"]["state"] == "working"


@pytest.mark.asyncio
async def test_stream_invalid_method_returns_error(app):
    req = _make_request()
    req["method"] = "tasks/unknown"
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as client:
        resp = await client.post("/stream", json=req)

    assert resp.status_code == 200
    data = resp.json()
    assert data["error"]["code"] == -32601

from __future__ import annotations

import logging
from collections.abc import AsyncGenerator

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse
from sse_starlette.sse import EventSourceResponse

from app.models.a2a import (
    A2AMessage,
    JsonRpcError,
    JsonRpcRequest,
    JsonRpcResponse,
    MessagePart,
    TaskResult,
    TaskStatus,
)

logger = logging.getLogger(__name__)

router = APIRouter()

SUPPORTED_METHODS = {"tasks/send", "tasks/sendSubscribe"}


def _error_response(req_id: str | None, code: int, message: str) -> JSONResponse:
    return JSONResponse(
        JsonRpcError(id=req_id, error={"code": code, "message": message}).model_dump(
            by_alias=True
        )
    )


def _validate_request(body: dict) -> JsonRpcRequest | JSONResponse:
    try:
        rpc_request = JsonRpcRequest.model_validate(body)
    except Exception:
        return _error_response(body.get("id"), -32600, "Invalid request")

    if rpc_request.method not in SUPPORTED_METHODS:
        return _error_response(rpc_request.id, -32601, "Method not found")

    return rpc_request


@router.post("/")
async def handle_task(request: Request) -> JSONResponse:
    try:
        body = await request.json()
    except Exception:
        return _error_response(None, -32700, "Parse error")

    result = _validate_request(body)
    if isinstance(result, JSONResponse):
        return result
    rpc_request = result

    agent = request.app.state.agent
    text = rpc_request.params.message.parts[0].text
    context_id = rpc_request.params.context_id

    try:
        response_text = await agent.invoke(text, context_id=context_id)
    except Exception:
        logger.exception("LLM invocation failed")
        return _error_response(rpc_request.id, -32603, "Internal error")

    response = JsonRpcResponse(
        id=rpc_request.id,
        result=TaskResult(
            id=rpc_request.params.id,
            context_id=context_id,
            status=TaskStatus(
                state="completed",
                message=A2AMessage(
                    role="agent",
                    parts=[MessagePart(text=response_text)],
                ),
            ),
            final=True,
        ),
    )
    return JSONResponse(response.model_dump(by_alias=True))


@router.post("/stream")
async def handle_task_stream(request: Request):
    try:
        body = await request.json()
    except Exception:
        return _error_response(None, -32700, "Parse error")

    result = _validate_request(body)
    if isinstance(result, JSONResponse):
        return result
    rpc_request = result

    agent = request.app.state.agent
    text = rpc_request.params.message.parts[0].text
    context_id = rpc_request.params.context_id

    async def event_generator() -> AsyncGenerator[dict, None]:
        accumulated = ""
        pending_chunk = None

        async for chunk in agent.astream(text, context_id=context_id):
            if pending_chunk is not None:
                accumulated += pending_chunk
                event_data = JsonRpcResponse(
                    id=rpc_request.id,
                    result=TaskResult(
                        id=rpc_request.params.id,
                        context_id=context_id,
                        status=TaskStatus(
                            state="working",
                            message=A2AMessage(
                                role="agent",
                                parts=[MessagePart(text=accumulated)],
                            ),
                        ),
                        final=False,
                    ),
                )
                yield {"event": "message", "data": event_data.model_dump_json(by_alias=True)}
            pending_chunk = chunk

        if pending_chunk is not None:
            accumulated += pending_chunk
            final_data = JsonRpcResponse(
                id=rpc_request.id,
                result=TaskResult(
                    id=rpc_request.params.id,
                    context_id=context_id,
                    status=TaskStatus(
                        state="completed",
                        message=A2AMessage(
                            role="agent",
                            parts=[MessagePart(text=accumulated)],
                        ),
                    ),
                    final=True,
                ),
            )
            yield {"event": "message", "data": final_data.model_dump_json(by_alias=True)}

    return EventSourceResponse(event_generator())

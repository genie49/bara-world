"""Wide Event logging middleware for FastAPI.

Python equivalent of the Kotlin WideEvent pattern:
- One structured log per request
- contextvars for async-safe context propagation
- Dev: text format, Prod: JSON format
"""

from __future__ import annotations

import logging
import os
import time
import uuid
from contextvars import ContextVar
from typing import Any

from starlette.middleware.base import BaseHTTPMiddleware, RequestResponseEndpoint
from starlette.requests import Request
from starlette.responses import Response

_wide_event_context: ContextVar[dict[str, Any]] = ContextVar("wide_event", default={})
_wide_event_message: ContextVar[str | None] = ContextVar("wide_event_message", default=None)

logger = logging.getLogger("wide-event")


class WideEvent:
    """Thread/async-safe structured logging context."""

    @staticmethod
    def put(key: str, value: Any) -> None:
        ctx = _wide_event_context.get()
        ctx[key] = value
        _wide_event_context.set(ctx)

    @staticmethod
    def message(msg: str) -> None:
        _wide_event_message.set(msg)

    @staticmethod
    def get_all() -> dict[str, Any]:
        return dict(_wide_event_context.get())

    @staticmethod
    def get_message() -> str | None:
        return _wide_event_message.get()

    @staticmethod
    def clear() -> None:
        _wide_event_context.set({})
        _wide_event_message.set(None)


class RequestLoggingMiddleware(BaseHTTPMiddleware):
    """Per-request wide event logging middleware."""

    async def dispatch(self, request: Request, call_next: RequestResponseEndpoint) -> Response:
        if request.url.path.startswith("/health"):
            return await call_next(request)

        WideEvent.clear()

        request_id = request.headers.get("x-correlation-id", str(uuid.uuid4()))
        start_time = time.monotonic()

        WideEvent.put("request_id", request_id)
        WideEvent.put("method", request.method)
        WideEvent.put("path", request.url.path)

        status_code = 500
        error_info: str | None = None
        try:
            response = await call_next(request)
            status_code = response.status_code
            response.headers["x-correlation-id"] = request_id
            return response
        except Exception as exc:
            error_info = f"{type(exc).__name__}: {exc}"
            raise
        finally:
            duration_ms = round((time.monotonic() - start_time) * 1000)

            fields = WideEvent.get_all()
            fields["status_code"] = status_code
            fields["duration_ms"] = duration_ms
            if error_info:
                fields["error"] = error_info

            msg = WideEvent.get_message() or f"{request.method} {request.url.path}"

            if error_info or status_code >= 500:
                logger.error(msg, extra=fields)
            elif status_code >= 400:
                logger.warning(msg, extra=fields)
            else:
                logger.info(msg, extra=fields)

            WideEvent.clear()


def setup_logging() -> None:
    """Configure logging based on APP_ENVIRONMENT."""
    env = os.getenv("APP_ENVIRONMENT", "dev")
    root = logging.getLogger()
    root.setLevel(logging.INFO)

    if root.handlers:
        return

    handler = logging.StreamHandler()

    if env == "prod":
        import json

        class JsonFormatter(logging.Formatter):
            def format(self, record: logging.LogRecord) -> str:
                log_data: dict[str, Any] = {
                    "timestamp": self.formatTime(record),
                    "level": record.levelname,
                    "logger": record.name,
                    "message": record.getMessage(),
                    "service": os.getenv("SERVICE_NAME", "bara-default-agent"),
                    "version": os.getenv("APP_VERSION", "local"),
                    "environment": env,
                }
                if hasattr(record, "request_id"):
                    for key in ("request_id", "method", "path", "status_code",
                                "duration_ms", "error"):
                        val = getattr(record, key, None)
                        if val is not None:
                            log_data[key] = val
                if record.exc_info and record.exc_info[1]:
                    log_data["exception"] = str(record.exc_info[1])
                return json.dumps(log_data, ensure_ascii=False)

        handler.setFormatter(JsonFormatter())
    else:
        handler.setFormatter(
            logging.Formatter(
                "%(asctime)s %(levelname)-5s [%(name)s] %(message)s | %(extra_fields)s",
                datefmt="%H:%M:%S",
                defaults={"extra_fields": ""},
            )
        )

        class DevFormatter(logging.Formatter):
            def format(self, record: logging.LogRecord) -> str:
                extras = {k: v for k, v in record.__dict__.items()
                          if k not in logging.LogRecord(
                              "", 0, "", 0, None, None, None
                          ).__dict__ and k != "extra_fields"}
                extra_str = " ".join(f"{k}={v}" for k, v in extras.items()) if extras else ""
                record.extra_fields = extra_str
                return super().format(record)

        handler.setFormatter(DevFormatter(
            "%(asctime)s %(levelname)-5s [%(name)s] %(message)s | %(extra_fields)s",
            datefmt="%H:%M:%S",
        ))

    root.addHandler(handler)

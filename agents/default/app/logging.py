"""Wide Event logging middleware for FastAPI.

Python equivalent of the Kotlin WideEvent pattern:
- One structured log per request
- contextvars for async-safe context propagation
- Dev: colored text format, Prod: JSON format
"""

from __future__ import annotations

import json
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

# wide-event 필드 키 (dev 포맷에서 순서 보장)
_FIELD_ORDER = [
    "request_id", "method", "path", "status_code", "duration_ms",
    "task_id", "context_id", "rpc_method", "outcome", "streaming", "error",
]


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


# ── Formatters ─────────────────────────────────────────────


class DevFormatter(logging.Formatter):
    """Dev: 사람이 읽기 편한 컬러 텍스트 포맷.

    Example:
      16:51:04 INFO  [wide-event] task completed | status=200 duration=42ms task_id=task-1
    """

    COLORS = {
        "DEBUG": "\033[36m",     # cyan
        "INFO": "\033[32m",      # green
        "WARNING": "\033[33m",   # yellow
        "ERROR": "\033[31m",     # red
        "CRITICAL": "\033[35m",  # magenta
    }
    RESET = "\033[0m"

    def format(self, record: logging.LogRecord) -> str:
        color = self.COLORS.get(record.levelname, "")
        ts = self.formatTime(record, "%H:%M:%S")
        level = f"{color}{record.levelname:<5}{self.RESET}"
        name = f"\033[36m{record.name}\033[0m"

        # extra 필드를 순서대로 포맷
        fields = {}
        for key in _FIELD_ORDER:
            val = getattr(record, key, None)
            if val is not None:
                fields[key] = val
        # 나머지 커스텀 필드
        for key, val in record.__dict__.items():
            if key not in fields and key not in _DEFAULT_RECORD_KEYS and val is not None:
                fields[key] = val

        if fields:
            parts = []
            for k, v in fields.items():
                if k == "duration_ms":
                    parts.append(f"duration={v}ms")
                elif k == "status_code":
                    code_color = "\033[32m" if v < 400 else "\033[33m" if v < 500 else "\033[31m"
                    parts.append(f"status={code_color}{v}{self.RESET}")
                elif k == "error":
                    parts.append(f"error=\033[31m{v}{self.RESET}")
                else:
                    parts.append(f"{k}={v}")
            field_str = " ".join(parts)
            return f"{ts} {level} [{name}] {record.getMessage()} | {field_str}"

        return f"{ts} {level} [{name}] {record.getMessage()}"


class JsonFormatter(logging.Formatter):
    """Prod: 구조화 JSON 포맷 (Fluent Bit/Loki 수집용)."""

    def format(self, record: logging.LogRecord) -> str:
        log_data: dict[str, Any] = {
            "timestamp": self.formatTime(record, "%Y-%m-%dT%H:%M:%S"),
            "level": record.levelname,
            "logger": record.name,
            "message": record.getMessage(),
            "service": os.getenv("SERVICE_NAME", "bara-default-agent"),
            "version": os.getenv("APP_VERSION", "local"),
            "environment": "prod",
        }
        for key in _FIELD_ORDER:
            val = getattr(record, key, None)
            if val is not None:
                log_data[key] = val
        if record.exc_info and record.exc_info[1]:
            log_data["exception"] = str(record.exc_info[1])
        return json.dumps(log_data, ensure_ascii=False)


# LogRecord 기본 키 — extra 필드 추출 시 제외용
_DEFAULT_RECORD_KEYS = set(logging.LogRecord("", 0, "", 0, None, None, None).__dict__.keys())


def setup_logging() -> None:
    """Configure logging based on APP_ENVIRONMENT."""
    env = os.getenv("APP_ENVIRONMENT", "dev")
    root = logging.getLogger()
    root.setLevel(logging.INFO)

    if root.handlers:
        return

    handler = logging.StreamHandler()
    handler.setFormatter(JsonFormatter() if env == "prod" else DevFormatter())
    root.addHandler(handler)

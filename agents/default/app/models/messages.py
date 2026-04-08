from __future__ import annotations

from typing import Any

from pydantic import BaseModel


class Part(BaseModel):
    text: str | None = None
    metadata: dict[str, Any] | None = None
    media_type: str | None = None


class Message(BaseModel):
    message_id: str
    role: str
    parts: list[Part]
    context_id: str | None = None
    task_id: str | None = None
    metadata: dict[str, Any] | None = None
    extensions: list[str] | None = None
    reference_task_ids: list[str] | None = None


class TaskMessage(BaseModel):
    task_id: str
    context_id: str | None = None
    user_id: str
    request_id: str
    result_topic: str
    allowed_agents: list[str] | None = None
    message: Message


class TaskStatus(BaseModel):
    state: str
    message: Message | None = None
    timestamp: str | None = None


class TaskResult(BaseModel):
    task_id: str
    context_id: str | None = None
    user_id: str
    request_id: str
    agent_id: str
    status: TaskStatus
    final: bool


class HeartbeatMessage(BaseModel):
    agent_id: str
    timestamp: str

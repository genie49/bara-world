from __future__ import annotations

from typing import Any

from pydantic import BaseModel, Field


class MessagePart(BaseModel):
    type: str = "text"
    text: str


class A2AMessage(BaseModel):
    role: str
    parts: list[MessagePart]


class TaskSendParams(BaseModel):
    id: str
    message: A2AMessage
    context_id: str | None = Field(default=None, alias="contextId")

    model_config = {"populate_by_name": True}


class JsonRpcRequest(BaseModel):
    jsonrpc: str = "2.0"
    id: str
    method: str
    params: TaskSendParams


class TaskStatus(BaseModel):
    state: str
    message: A2AMessage


class TaskResult(BaseModel):
    id: str
    context_id: str | None = Field(default=None, alias="contextId")
    status: TaskStatus
    final: bool

    model_config = {"populate_by_name": True}


class JsonRpcResponse(BaseModel):
    jsonrpc: str = "2.0"
    id: str
    result: TaskResult

    model_config = {"serialize_by_alias": True}


class JsonRpcError(BaseModel):
    jsonrpc: str = "2.0"
    id: str | None = None
    error: dict[str, Any]

    model_config = {"serialize_by_alias": True}


class AgentCard(BaseModel):
    name: str
    description: str
    version: str
    default_input_modes: list[str] = Field(alias="defaultInputModes")
    default_output_modes: list[str] = Field(alias="defaultOutputModes")
    capabilities: dict[str, Any]
    skills: list[dict[str, Any]]

    model_config = {"populate_by_name": True, "serialize_by_alias": True}

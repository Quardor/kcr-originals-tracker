"""WebSocket chat message schemas (§7): every frame is validated, in and out."""

from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import Field

from kcr_ml.api.schemas.common import ApiModel, JsonValue


class WsUserMessage(ApiModel):
    """Client → server: one chat question."""

    type: Literal["user_message"]
    content: str = Field(min_length=1, max_length=4000)


class WsAssistantDelta(ApiModel):
    type: Literal["assistant_delta"] = "assistant_delta"
    content: str


class WsToolStatus(ApiModel):
    type: Literal["tool_status"] = "tool_status"
    tool: str


class WsDataMessage(ApiModel):
    type: Literal["data"] = "data"
    title: str
    columns: list[str]
    rows: list[list[JsonValue]]


class WsAssistantDone(ApiModel):
    type: Literal["assistant_done"] = "assistant_done"
    message_id: str
    total_tokens: int | None = None


class WsErrorMessage(ApiModel):
    type: Literal["error"] = "error"
    code: str
    message: str
    timestamp: datetime


WsServerMessage = WsAssistantDelta | WsToolStatus | WsDataMessage | WsAssistantDone | WsErrorMessage

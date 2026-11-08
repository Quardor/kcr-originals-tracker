"""WS /api/v1/ai/chat — chat protocol of §7 (token in query, JSON frames).

Browsers cannot set the Authorization header on WebSocket, so the MVP carries
the access token in the query parameter (accepted trade-off, §8). Every frame
is validated by a Pydantic schema; invalid input closes the connection with an
``error`` frame. History lives in the memory of the connection only (§7).
"""

from __future__ import annotations

import json
import logging
from datetime import date
from typing import Annotated, Any

from fastapi import APIRouter, Depends, WebSocket, WebSocketDisconnect

from kcr_ml.api.deps import get_settings, get_ws_data_source, get_ws_llm
from kcr_ml.api.schemas.ws import (
    WsAssistantDelta,
    WsAssistantDone,
    WsDataMessage,
    WsServerMessage,
    WsToolStatus,
    WsUserMessage,
)
from kcr_ml.application.chat_service import ChatEvent, ChatSession, DataEvent, DeltaEvent, ToolStatusEvent
from kcr_ml.application.ports import DataSourcePort, LlmPort
from kcr_ml.config import Settings
from kcr_ml.exceptions import AppError, AuthenticationRequiredError, ValidationFailedError
from kcr_ml.infrastructure.security.jwt_auth import decode_access_token

logger = logging.getLogger(__name__)

router = APIRouter()


def _serialize(event: ChatEvent) -> WsServerMessage:
    if isinstance(event, DeltaEvent):
        return WsAssistantDelta(content=event.content)
    if isinstance(event, ToolStatusEvent):
        return WsToolStatus(tool=event.tool)
    if isinstance(event, DataEvent):
        return WsDataMessage(
            title=event.table.title,
            columns=[column.label for column in event.table.columns],
            rows=[list(row) for row in event.table.rows],
        )
    return WsAssistantDone(message_id=event.message_id, total_tokens=event.total_tokens)


async def _send_error(websocket: WebSocket, error: AppError) -> None:
    body: dict[str, Any] = {"type": "error", **error.to_body()}
    await websocket.send_text(json.dumps(body, ensure_ascii=False))


@router.websocket("/api/v1/ai/chat")
async def chat_endpoint(
    websocket: WebSocket,
    source: Annotated[DataSourcePort, Depends(get_ws_data_source)],
    llm: Annotated[LlmPort, Depends(get_ws_llm)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> None:
    """One connection = one chat session with bounded in-memory history."""
    await websocket.accept()
    try:
        decode_access_token(websocket.query_params.get("token", ""), settings.auth_jwt_secret)
    except AuthenticationRequiredError as error:
        await _send_error(websocket, error)
        await websocket.close(code=4401)
        return

    session = ChatSession(
        source,
        llm,
        today=date.today(),
        history_limit=settings.ai_chat_history_limit,
        max_tool_iterations=settings.ai_max_tool_iterations,
        max_table_rows=settings.ai_max_table_rows,
    )
    try:
        while True:
            raw = await websocket.receive_text()
            try:
                message = WsUserMessage.model_validate_json(raw)
            except ValueError:
                await _send_error(websocket, ValidationFailedError("Invalid chat message frame"))
                await websocket.close(code=1008)
                return
            try:
                async for event in session.handle(message.content):
                    frame = _serialize(event).model_dump(mode="json", by_alias=True)
                    await websocket.send_text(json.dumps(frame, ensure_ascii=False))
            except AppError as error:
                # controlled failure of one turn: report and keep the connection
                await _send_error(websocket, error)
    except WebSocketDisconnect:
        pass

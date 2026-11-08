"""Integration: WebSocket chat protocol (§7) via the ASGI test client."""

from __future__ import annotations

import contextlib
import json
from typing import Any

from fastapi.testclient import TestClient

from kcr_ml.application.ports import StreamDone, ToolCall
from tests.fakes import ScriptedLlm, make_token, text_script


def _receive_until(ws: Any, stop_type: str) -> list[dict[str, Any]]:
    frames: list[dict[str, Any]] = []
    while True:
        frame = json.loads(ws.receive_text())
        frames.append(frame)
        if frame["type"] in (stop_type, "error"):
            return frames


def test_chat_full_roundtrip(client: TestClient, llm_factory: Any) -> None:
    llm_factory(
        ScriptedLlm(
            [
                [ToolCall(id="c1", name="get_today_clients", arguments={}), StreamDone("tool_calls", None)],
                text_script("Этим клиентам нужен звонок сегодня.", tokens=33),
            ]
        )
    )
    token = make_token(role="OPERATOR", username="op.morozova")
    with client.websocket_connect(f"/api/v1/ai/chat?token={token}") as ws:
        ws.send_text(json.dumps({"type": "user_message", "content": "Кому сегодня позвонить?"}))
        frames = _receive_until(ws, "assistant_done")
    types = [frame["type"] for frame in frames]
    assert types == ["tool_status", "data", "assistant_delta", "assistant_done"]
    assert frames[0]["tool"] == "get_today_clients"
    data = frames[1]
    assert data["title"] == "Клиенты"
    assert {row[0] for row in data["rows"]} == {"kcr_0002", "kcr_0008"}  # source numbers only
    assert frames[3]["totalTokens"] == 33


def test_chat_error_keeps_connection_open(client: TestClient, llm_factory: Any) -> None:
    from kcr_ml.application.ports import TextDelta
    from kcr_ml.exceptions import BackendUnavailableError

    class FailingLlm(ScriptedLlm):
        async def chat_stream(self, messages: Any, tools: Any = ()) -> Any:
            yield TextDelta("начало")
            raise BackendUnavailableError("kcr-backend request failed")

    llm_factory(FailingLlm())
    token = make_token(role="ADMIN", username="admin")
    with client.websocket_connect(f"/api/v1/ai/chat?token={token}") as ws:
        ws.send_text(json.dumps({"type": "user_message", "content": "вопрос"}))
        frames = _receive_until(ws, "error")
        assert frames[-1]["code"] == "BACKEND_UNAVAILABLE"
        # connection survives a controlled per-turn error
        ws.send_text(json.dumps({"type": "user_message", "content": "ещё вопрос"}))
        frames = _receive_until(ws, "error")
        assert frames[-1]["code"] == "BACKEND_UNAVAILABLE"


def test_chat_invalid_frame_closes_with_error(client: TestClient) -> None:
    token = make_token(role="ADMIN", username="admin")
    with client.websocket_connect(f"/api/v1/ai/chat?token={token}") as ws:
        ws.send_text("не json вообще")
        frame = json.loads(ws.receive_text())
        assert frame["type"] == "error"
        assert frame["code"] == "VALIDATION_FAILED"
        # the server closes the connection afterwards; the transport then raises
        with contextlib.suppress(Exception):
            ws.receive_text()


def test_chat_bad_token_rejected(client: TestClient) -> None:
    with client.websocket_connect("/api/v1/ai/chat?token=garbage") as ws:
        frame = json.loads(ws.receive_text())
        assert frame["type"] == "error"
        assert frame["code"] == "AUTHENTICATION_REQUIRED"

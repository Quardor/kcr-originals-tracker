"""Integration: WebSocket chat protocol (§7) via the ASGI test client."""

from __future__ import annotations

import contextlib
import json
from typing import Any

import pytest
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from kcr_ml.application.ports import StreamDone, ToolCall
from tests.fakes import TEST_INTERNAL_TOKEN, ScriptedLlm, backend_headers, text_script


def _receive_until(ws: Any, stop_type: str) -> list[dict[str, Any]]:
    frames: list[dict[str, Any]] = []
    while True:
        frame = json.loads(ws.receive_text())
        frames.append(frame)
        if frame["type"] in (stop_type, "error"):
            return frames


def test_chat_full_roundtrip(client: TestClient, llm_factory: Any, admin_headers: dict[str, str]) -> None:
    llm_factory(
        ScriptedLlm(
            [
                [ToolCall(id="c1", name="get_today_clients", arguments={}), StreamDone("tool_calls", None)],
                text_script("Этим клиентам нужен звонок сегодня.", tokens=33),
            ]
        )
    )
    with client.websocket_connect("/api/v1/ai/chat", headers=admin_headers) as ws:
        ws.send_text(json.dumps({"type": "user_message", "content": "Кому сегодня позвонить?"}))
        frames = _receive_until(ws, "assistant_done")
    types = [frame["type"] for frame in frames]
    assert types == ["tool_status", "data", "assistant_delta", "assistant_done"]
    assert frames[0]["tool"] == "get_today_clients"
    data = frames[1]
    assert data["title"] == "Клиенты"
    assert {row[0] for row in data["rows"]} == {"kcr_0002", "kcr_0008"}  # source numbers only
    assert frames[3]["totalTokens"] == 33


def test_chat_error_keeps_connection_open(client: TestClient, llm_factory: Any, admin_headers: dict[str, str]) -> None:
    from kcr_ml.application.ports import TextDelta
    from kcr_ml.exceptions import BackendUnavailableError

    class FailingLlm(ScriptedLlm):
        async def chat_stream(self, messages: Any, tools: Any = ()) -> Any:
            yield TextDelta("начало")
            raise BackendUnavailableError("kcr-backend request failed")

    llm_factory(FailingLlm())
    with client.websocket_connect("/api/v1/ai/chat", headers=admin_headers) as ws:
        ws.send_text(json.dumps({"type": "user_message", "content": "вопрос"}))
        frames = _receive_until(ws, "error")
        assert frames[-1]["code"] == "BACKEND_UNAVAILABLE"
        # connection survives a controlled per-turn error
        ws.send_text(json.dumps({"type": "user_message", "content": "ещё вопрос"}))
        frames = _receive_until(ws, "error")
        assert frames[-1]["code"] == "BACKEND_UNAVAILABLE"


def test_chat_invalid_frame_closes_with_error(client: TestClient, admin_headers: dict[str, str]) -> None:
    with client.websocket_connect("/api/v1/ai/chat", headers=admin_headers) as ws:
        ws.send_text("не json вообще")
        frame = json.loads(ws.receive_text())
        assert frame["type"] == "error"
        assert frame["code"] == "VALIDATION_FAILED"
        # the server closes the connection afterwards; the transport then raises
        with contextlib.suppress(Exception):
            ws.receive_text()


def test_chat_refuses_foreign_callers_before_accept(client: TestClient) -> None:
    no_headers: dict[str, str] = {}
    wrong_internal_token = backend_headers(internal_token="wrong-internal-token-0123456789abcdef")
    no_identity = {"Authorization": "Bearer access-token", "X-Internal-Token": TEST_INTERNAL_TOKEN}
    for headers in (no_headers, wrong_internal_token, no_identity):
        with (
            pytest.raises(WebSocketDisconnect) as exc_info,
            client.websocket_connect("/api/v1/ai/chat", headers=headers),
        ):
            pass
        assert exc_info.value.code == 4401

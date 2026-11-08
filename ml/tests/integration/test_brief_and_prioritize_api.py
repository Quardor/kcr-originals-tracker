"""Integration: brief (UC-2) and prioritize (UC-3) endpoints."""

from __future__ import annotations

import json
from typing import Any

from fastapi.testclient import TestClient

from tests.fakes import ScriptedLlm, text_script

CRITERIA = json.dumps({"exclude_client_types": ["VIP"], "min_untransferred_percent": 25, "in_work": False})


def test_brief_returns_facts_and_summary(client: TestClient, admin_headers: dict[str, str], llm_factory: Any) -> None:
    llm_factory(ScriptedLlm([text_script("Готовьте претензионную работу.")]))
    response = client.get("/api/v1/ai/clients/2/brief", headers=admin_headers)
    assert response.status_code == 200
    body = response.json()
    assert body["client"]["kcrCode"] == "kcr_0002"
    assert body["client"]["clientType"] == "VIP"
    assert body["documents"]["notTransferredDocuments"] == 10
    assert body["documents"]["percentNotTransferred"] == 20
    assert body["dynamics"]["category"] == "REAPPEARED"
    assert body["recentEvents"][0]["type"] == "CALL"
    assert body["claims"][0]["number"] == "П-101"
    assert body["summary"] == "Готовьте претензионную работу."


def test_brief_unknown_client_is_404(client: TestClient, admin_headers: dict[str, str]) -> None:
    response = client.get("/api/v1/ai/clients/999/brief", headers=admin_headers)
    assert response.status_code == 404
    assert response.json()["code"] == "RESOURCE_NOT_FOUND"


def test_prioritize_admin_flow(client: TestClient, admin_headers: dict[str, str], llm_factory: Any) -> None:
    llm = llm_factory(ScriptedLlm([text_script(CRITERIA), text_script("Топ — Альфа-Сервис.")]))
    response = client.post(
        "/api/v1/ai/prioritize",
        json={"prompt": "Не VIP, непередано больше 25%, не в работе", "limit": 5},
        headers=admin_headers,
    )
    assert response.status_code == 200
    body = response.json()
    assert body["criteria"]["excludeClientTypes"] == ["VIP"]
    assert [item["kcrCode"] for item in body["items"]] == ["kcr_0001", "kcr_0010", "kcr_0006"]
    assert body["items"][0]["score"] >= body["items"][1]["score"]
    assert body["items"][0]["reasons"]
    assert body["explanation"] == "Топ — Альфа-Сервис."
    assert len(llm.calls) == 2


def test_prioritize_invalid_llm_output_is_502(
    client: TestClient, admin_headers: dict[str, str], llm_factory: Any
) -> None:
    llm_factory(ScriptedLlm([text_script("мусор"), text_script("ещё мусор")]))
    response = client.post("/api/v1/ai/prioritize", json={"prompt": "кто"}, headers=admin_headers)
    assert response.status_code == 502
    assert response.json()["code"] == "LLM_INVALID_RESPONSE"

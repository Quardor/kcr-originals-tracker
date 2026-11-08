"""Контрольные сценарии приёмки из ТЗ (ARCHITECTURE.md §14).

LLM заменён детерминированной заглушкой: проверяются вызовы инструментов,
структура ответов и совпадение выборок с ручным фильтром по сидах. Живая модель
прогоняется по тем же сценариям вручную.
"""

from __future__ import annotations

import json
from typing import Any

from fastapi.testclient import TestClient

from kcr_ml.application.ports import StreamDone, ToolCall
from tests.fakes import ScriptedLlm, make_token, text_script

ADMIN = {"Authorization": f"Bearer {make_token(role='ADMIN', username='admin')}"}


def _ws_receive_until(ws: Any, stop_type: str) -> list[dict[str, Any]]:
    frames: list[dict[str, Any]] = []
    while True:
        frame = json.loads(ws.receive_text())
        frames.append(frame)
        if frame["type"] in (stop_type, "error"):
            return frames


# Сценарий 1: «не VIP, не передано > 25%, не в работе» — выборка совпадает с ручным фильтром.
def test_scenario_1_not_vip_over_25_not_in_work(client: TestClient, llm_factory: Any) -> None:
    criteria = json.dumps(
        {
            "exclude_client_types": ["VIP"],
            "min_untransferred_percent": 25,
            "in_work": False,
        }
    )
    llm_factory(ScriptedLlm([text_script(criteria), text_script("Пояснение.")]))
    prompt = "Покажи клиентов не VIP, у которых не передано больше 25% документов и которые ещё не в работе"
    response = client.post(
        "/api/v1/ai/prioritize",
        json={"prompt": prompt},
        headers=ADMIN,
    )
    assert response.status_code == 200
    items = response.json()["items"]
    # ручной фильтр по данным: Альфа 45% (NOT_IN_WORK), Вектор 30%, Ладога 25%
    assert [item["kcrCode"] for item in items] == ["kcr_0001", "kcr_0010", "kcr_0006"]


# Сценарий 2: «у кого не меняется несколько срезов подряд» — UNCHANGED_OR_GROWING/PERSISTENT.
def test_scenario_2_stagnating_debt(client: TestClient, llm_factory: Any) -> None:
    criteria = json.dumps({"dynamics_categories": ["UNCHANGED_OR_GROWING", "PERSISTENT"]})
    llm_factory(ScriptedLlm([text_script(criteria), text_script("Долг стоит на месте.")]))
    response = client.post(
        "/api/v1/ai/prioritize",
        json={"prompt": "У кого количество непереданных документов не меняется несколько срезов подряд?"},
        headers=ADMIN,
    )
    assert response.status_code == 200
    assert [item["kcrCode"] for item in response.json()["items"]] == ["kcr_0001", "kcr_0010"]


# Сценарий 3: «после завершения цикла снова появились» — REAPPEARED.
def test_scenario_3_reappeared(client: TestClient, llm_factory: Any) -> None:
    criteria = json.dumps({"dynamics_categories": ["REAPPEARED"]})
    llm_factory(ScriptedLlm([text_script(criteria), text_script("Один клиент с возвратом долга.")]))
    response = client.post(
        "/api/v1/ai/prioritize",
        json={"prompt": "У кого после завершения предыдущего цикла снова появились непереданные документы?"},
        headers=ADMIN,
    )
    assert response.status_code == 200
    assert [item["kcrCode"] for item in response.json()["items"]] == ["kcr_0002"]


# Сценарий 4: «кому сегодня требуется внимание» — чат, инструмент get_today_clients, next_contact_at <= сегодня.
def test_scenario_4_today_attention_via_chat(client: TestClient, llm_factory: Any) -> None:
    llm_factory(
        ScriptedLlm(
            [
                [ToolCall(id="c1", name="get_today_clients", arguments={}), StreamDone("tool_calls", None)],
                text_script("Два клиента: Бета-Групп и Иртыш.", tokens=21),
            ]
        )
    )
    token = make_token(role="ADMIN", username="admin")
    with client.websocket_connect(f"/api/v1/ai/chat?token={token}") as ws:
        ws.send_text(
            json.dumps({"type": "user_message", "content": "Покажи клиентов, которым сегодня требуется внимание"})
        )
        frames = _ws_receive_until(ws, "assistant_done")
    data = next(frame for frame in frames if frame["type"] == "data")
    assert {row[0] for row in data["rows"]} == {"kcr_0002", "kcr_0008"}


# Сценарий 5: сводка перед звонком — все числа из данных бэкенда.
def test_scenario_5_brief_numbers_from_backend(client: TestClient, llm_factory: Any) -> None:
    llm_factory(ScriptedLlm([text_script("Сводка по Бета-Групп.")]))
    response = client.get("/api/v1/ai/clients/2/brief", headers=ADMIN)
    assert response.status_code == 200
    body = response.json()
    assert body["documents"]["notTransferredDocuments"] == 10
    assert body["documents"]["totalDocuments"] == 50
    assert body["documents"]["percentNotTransferred"] == 20
    assert body["dynamics"]["category"] == "REAPPEARED"
    assert body["recentEvents"][0]["agreement"] == "Передаст оригиналы до 20 ноября"
    assert body["nextActionDate"] == "2026-11-06"


# Сценарий 6: приоритетная выборка по нескольким признакам — критерии соответствуют тексту запроса.
def test_scenario_6_multi_factor_criteria(client: TestClient, llm_factory: Any) -> None:
    criteria = json.dumps(
        {
            "exclude_client_types": ["VIP"],
            "min_untransferred_percent": 25,
            "in_work": False,
            "assigned": False,
            "sort_direction": "desc",
        }
    )
    llm_factory(ScriptedLlm([text_script(criteria), text_script("Порядок объяснён.")]))
    response = client.post(
        "/api/v1/ai/prioritize",
        json={"prompt": "не VIP, непередано больше 25%, не в работе, без ответственного, сначала самые срочные"},
        headers=ADMIN,
    )
    assert response.status_code == 200
    got = response.json()["criteria"]
    assert got["excludeClientTypes"] == ["VIP"]
    assert got["minUntransferredPercent"] == 25
    assert got["inWork"] is False
    assert got["assigned"] is False
    assert got["sortDirection"] == "desc"


# Сценарий 7 (негативные): вопрос вне данных и просьба мутации — отказ, данных не трогали.
def test_scenario_7_refusals(client: TestClient, llm_factory: Any) -> None:
    llm_factory(
        ScriptedLlm(
            [
                text_script("Я работаю только с данными сервиса КЦР — погоду не подскажу."),
                text_script("Я не изменяю данные и этапы: переведите клиента в работе в основном интерфейсе."),
            ]
        )
    )
    token = make_token(role="OPERATOR", username="op.morozova")
    with client.websocket_connect(f"/api/v1/ai/chat?token={token}") as ws:
        ws.send_text(json.dumps({"type": "user_message", "content": "Какая погода в Екатеринбурге?"}))
        frames = _ws_receive_until(ws, "assistant_done")
        assert [frame["type"] for frame in frames] == ["assistant_delta", "assistant_done"]
        assert "только с данными" in frames[0]["content"]

        ws.send_text(json.dumps({"type": "user_message", "content": "Переведи kcr_0002 на этап Претензионная работа"}))
        frames = _ws_receive_until(ws, "assistant_done")
        assert [frame["type"] for frame in frames] == ["assistant_delta", "assistant_done"]
        assert "не изменяю" in frames[0]["content"]
    # ни одного обращения к данным: read-only подтверждён протоколом, а не обещанием

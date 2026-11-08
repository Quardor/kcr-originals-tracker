"""Integration: analytics endpoints (UC-4) through the ASGI transport."""

from __future__ import annotations

from fastapi.testclient import TestClient


def test_summary(client: TestClient, admin_headers: dict[str, str]) -> None:
    response = client.get("/api/v1/ai/analytics/summary", headers=admin_headers)
    assert response.status_code == 200
    body = response.json()
    assert body["totals"] == {"clients": 10, "withDebt": 8, "notTransferredDocuments": 121}
    assert body["byDynamicsCategory"]["PERSISTENT"] == 1
    assert body["byStage"]["IN_POOL"] == 2
    assert "generatedAt" in body


def test_dynamics_series_shape(client: TestClient, admin_headers: dict[str, str]) -> None:
    response = client.get("/api/v1/ai/analytics/dynamics", headers=admin_headers)
    assert response.status_code == 200
    body = response.json()
    assert [row["period"] for row in body["series"]] == ["1 кв. 2026", "2 кв. 2026", "3 кв. 2026"]
    # canonical snake_case series keys per §7
    for key in (
        "no_debt",
        "first_occurrence",
        "decreasing",
        "unchanged_or_growing",
        "reappeared",
        "persistent",
        "newcomer_risk",
    ):
        assert key in body["series"][0]
    assert body["series"][2]["persistent"] == 1
    assert body["series"][2]["reappeared"] == 1


def test_top_debt_limit(client: TestClient, admin_headers: dict[str, str]) -> None:
    response = client.get("/api/v1/ai/analytics/top-debt", params={"limit": 3}, headers=admin_headers)
    assert response.status_code == 200
    items = response.json()["items"]
    assert [item["kcrCode"] for item in items] == ["kcr_0001", "kcr_0006", "kcr_0010"]
    assert items[0]["percentNotTransferred"] == 45


def test_operators(client: TestClient, admin_headers: dict[str, str]) -> None:
    response = client.get("/api/v1/ai/analytics/operators", headers=admin_headers)
    assert response.status_code == 200
    body = response.json()
    by_user = {item["username"]: item for item in body["operators"]}
    assert by_user["op.morozova"]["dueToday"] == 2
    assert body["totals"]["unassignedInPool"] == 2

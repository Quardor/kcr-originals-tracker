"""Integration: health, authentication, roles and the error contract (§7)."""

from __future__ import annotations

from fastapi.testclient import TestClient


def test_health_is_public(client: TestClient) -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_protected_endpoints_require_token(client: TestClient) -> None:
    for path in ("/api/v1/ai/analytics/summary", "/api/v1/ai/analytics/dynamics"):
        response = client.get(path)
        assert response.status_code == 401
        body = response.json()
        assert body["code"] == "AUTHENTICATION_REQUIRED"
        assert "timestamp" in body


def test_garbage_token_is_rejected(client: TestClient) -> None:
    response = client.get("/api/v1/ai/analytics/summary", headers={"Authorization": "Bearer garbage"})
    assert response.status_code == 401
    assert response.json()["code"] == "AUTHENTICATION_REQUIRED"


def test_wrong_secret_token_is_rejected(client: TestClient) -> None:
    from tests.fakes import make_token

    foreign = make_token(role="ADMIN", secret="another-wrong-secret-0123456789abcdef")
    response = client.get("/api/v1/ai/analytics/summary", headers={"Authorization": f"Bearer {foreign}"})
    assert response.status_code == 401


def test_prioritize_requires_admin(client: TestClient, operator_headers: dict[str, str]) -> None:
    response = client.post("/api/v1/ai/prioritize", json={"prompt": "кто в приоритете"}, headers=operator_headers)
    assert response.status_code == 403
    assert response.json()["code"] == "ACCESS_DENIED"


def test_export_requires_admin(client: TestClient, operator_headers: dict[str, str]) -> None:
    response = client.post(
        "/api/v1/ai/export/xlsx",
        json={"kind": "analytics_summary"},
        headers=operator_headers,
    )
    assert response.status_code == 403


def test_validation_error_returns_contract(client: TestClient, admin_headers: dict[str, str]) -> None:
    response = client.post("/api/v1/ai/prioritize", json={"prompt": ""}, headers=admin_headers)
    assert response.status_code == 400
    body = response.json()
    assert body["code"] == "VALIDATION_FAILED"
    assert body["message"]

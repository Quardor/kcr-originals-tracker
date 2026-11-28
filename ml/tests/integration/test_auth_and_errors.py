"""Integration: health, the backend internal token, identity headers and the error contract (§7)."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from tests.fakes import TEST_INTERNAL_TOKEN, backend_headers


def test_health_is_public(client: TestClient) -> None:
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_protected_endpoints_require_backend_token(client: TestClient) -> None:
    for path in ("/api/v1/ai/analytics/summary", "/api/v1/ai/analytics/dynamics"):
        # (a) no headers at all
        response = client.get(path)
        assert response.status_code == 401
        body = response.json()
        assert body["code"] == "INTERNAL_TOKEN_REJECTED"
        assert "timestamp" in body

        # (b) identity headers present, but the internal service token is wrong
        foreign = backend_headers(internal_token="wrong-internal-token-0123456789abcdef")
        response = client.get(path, headers=foreign)
        assert response.status_code == 401
        assert response.json()["code"] == "INTERNAL_TOKEN_REJECTED"

        # (c) valid internal token, but no asserted user identity
        no_identity = {"Authorization": "Bearer access-token", "X-Internal-Token": TEST_INTERNAL_TOKEN}
        response = client.get(path, headers=no_identity)
        assert response.status_code == 401
        assert response.json()["code"] == "INTERNAL_TOKEN_REJECTED"


def test_bearer_token_is_not_verified_here(client: TestClient) -> None:
    headers = backend_headers(token="not-a-jwt-at-all")
    response = client.get("/api/v1/ai/analytics/summary", headers=headers)
    assert response.status_code == 200


def test_missing_bearer_token_is_rejected(client: TestClient) -> None:
    no_bearer = {k: v for k, v in backend_headers().items() if k != "Authorization"}
    response = client.get("/api/v1/ai/analytics/summary", headers=no_bearer)
    assert response.status_code == 401
    assert response.json()["code"] == "AUTHENTICATION_REQUIRED"


def test_internal_token_must_be_long_enough() -> None:
    from kcr_ml.config import Settings

    with pytest.raises(ValidationError):
        Settings(
            _env_file=None,
            kcr_backend_base_url="http://backend.test",
            auth_internal_token="short",
            llm_provider="api",
            llm_base_url="http://llm.test/v1",
            llm_api_key="test-key",
            llm_model="test-model",
        )


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

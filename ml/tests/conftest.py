"""Shared fixtures: settings, app with overridden ports, ASGI client, backend headers."""

from __future__ import annotations

import os

from tests.fakes import TEST_INTERNAL_TOKEN

# kcr_ml.main instantiates the app at import time (uvicorn target), so the
# mandatory settings must exist before it is imported; fixtures override them.
os.environ.setdefault("KCR_BACKEND_BASE_URL", "http://backend.test")
os.environ.setdefault("AUTH_INTERNAL_TOKEN", TEST_INTERNAL_TOKEN)
os.environ.setdefault("LLM_PROVIDER", "api")
os.environ.setdefault("LLM_BASE_URL", "http://llm.test/v1")
os.environ.setdefault("LLM_API_KEY", "test-key")
os.environ.setdefault("LLM_MODEL", "test-model")

from collections.abc import Iterator
from typing import Any

import pytest
from fastapi.testclient import TestClient

from kcr_ml.api import deps
from kcr_ml.config import Settings
from kcr_ml.main import create_app
from tests.fakes import TODAY, FakeDataSource, ScriptedLlm, backend_headers


@pytest.fixture
def settings() -> Settings:
    return Settings(
        _env_file=None,
        kcr_backend_base_url="http://backend.test",
        auth_internal_token=TEST_INTERNAL_TOKEN,
        llm_provider="api",
        llm_base_url="http://llm.test/v1",
        llm_api_key="test-key",
        llm_model="test-model",
    )


@pytest.fixture
def fake_source() -> FakeDataSource:
    return FakeDataSource(today=TODAY)


@pytest.fixture
def app(settings: Settings, fake_source: FakeDataSource) -> Any:
    application = create_app(settings)
    application.dependency_overrides[deps.get_settings] = lambda: settings
    application.dependency_overrides[deps.get_data_source] = lambda: fake_source
    application.dependency_overrides[deps.get_ws_data_source] = lambda: fake_source
    application.dependency_overrides[deps.get_today] = lambda: TODAY
    return application


@pytest.fixture
def llm_factory(app: Any) -> Iterator[Any]:
    """Install a ScriptedLlm into the app (both REST and WS paths)."""

    def install(llm: ScriptedLlm) -> ScriptedLlm:
        app.dependency_overrides[deps.get_llm] = lambda: llm
        app.dependency_overrides[deps.get_ws_llm] = lambda: llm
        return llm

    return install


@pytest.fixture
def client(app: Any) -> Iterator[TestClient]:
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture
def admin_headers() -> dict[str, str]:
    return backend_headers(role="ADMIN", user_id=1, token="admin-token")


@pytest.fixture
def operator_headers() -> dict[str, str]:
    return backend_headers(role="OPERATOR", user_id=2, token="operator-token")

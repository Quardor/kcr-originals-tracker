"""FastAPI dependencies: principal, per-request data source, LLM and exporters.

The verified access token is forwarded to kcr-backend verbatim (§8): data
permissions are decided by the backend, not duplicated here. Every endpoint is
authenticated by default; public ones (health) simply do not use these deps.
"""

from __future__ import annotations

from collections.abc import AsyncIterator
from datetime import date
from functools import lru_cache
from typing import Annotated

from fastapi import Depends, Header, Request, WebSocket

from kcr_ml.application.ports import ExcelExporterPort, LlmPort
from kcr_ml.config import Settings
from kcr_ml.domain.criteria import ScoringWeights
from kcr_ml.domain.model import Principal
from kcr_ml.exceptions import AccessDeniedError, AuthenticationRequiredError
from kcr_ml.infrastructure.data.backend_api import BackendApiDataSource
from kcr_ml.infrastructure.excel.workbooks import OpenPyxlExporter
from kcr_ml.infrastructure.llm.factory import create_llm


@lru_cache
def get_settings() -> Settings:
    """Application settings (single instance per process)."""
    return Settings()


def get_token(authorization: Annotated[str | None, Header()] = None) -> str:
    """Extract the bearer access token issued by kcr-backend."""
    if not authorization or not authorization.lower().startswith("bearer "):
        raise AuthenticationRequiredError("Missing bearer access token")
    return authorization[7:].strip()


def get_principal(
    token: Annotated[str, Depends(get_token)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> Principal:
    """Verify the token signature and expiry; returns the acting user."""
    from kcr_ml.infrastructure.security.jwt_auth import decode_access_token

    return decode_access_token(token, settings.auth_jwt_secret)


def require_admin(principal: Annotated[Principal, Depends(get_principal)]) -> Principal:
    """UC-3/UC-5 are admin-only (§2)."""
    if not principal.is_admin:
        raise AccessDeniedError("Administrator role required")
    return principal


async def get_data_source(
    principal: Annotated[Principal, Depends(get_principal)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> AsyncIterator[BackendApiDataSource]:
    """Per-request read-only data source acting as the current user."""
    source = BackendApiDataSource(settings.kcr_backend_base_url, principal.token)
    try:
        yield source
    finally:
        await source.aclose()


def get_llm(
    request: Request,
    settings: Annotated[Settings, Depends(get_settings)],
) -> LlmPort:
    """Process-wide LLM adapter (created in the app lifespan when available)."""
    llm = getattr(request.app.state, "llm", None)
    if llm is None:
        llm = create_llm(settings)
        request.app.state.llm = llm
    return llm


def get_exporter() -> ExcelExporterPort:
    """Excel rendering adapter (stateless)."""
    return OpenPyxlExporter()


def get_scoring_weights(settings: Annotated[Settings, Depends(get_settings)]) -> ScoringWeights:
    """Deterministic UC-3 weights from configuration (§2)."""
    return ScoringWeights(
        untransferred=settings.ai_score_untransferred,
        dynamics=settings.ai_score_dynamics,
        urgency=settings.ai_score_urgency,
        stage=settings.ai_score_stage,
        vip=settings.ai_score_vip,
    )


def get_today() -> date:
    """Current date (injectable seam for tests)."""
    return date.today()


# --------------------------------------------------------------------------------------
# WebSocket variants (browsers cannot send the Authorization header on WS, §8)
# --------------------------------------------------------------------------------------


def get_ws_token(websocket: WebSocket) -> str:
    """Access token from the query parameter."""
    return websocket.query_params.get("token", "")


async def get_ws_data_source(
    token: Annotated[str, Depends(get_ws_token)],
    settings: Annotated[Settings, Depends(get_settings)],
) -> AsyncIterator[BackendApiDataSource]:
    """Per-connection read-only data source (token forwarded verbatim)."""
    source = BackendApiDataSource(settings.kcr_backend_base_url, token)
    try:
        yield source
    finally:
        await source.aclose()


def get_ws_llm(
    websocket: WebSocket,
    settings: Annotated[Settings, Depends(get_settings)],
) -> LlmPort:
    """Process-wide LLM adapter for WebSocket connections."""
    llm = getattr(websocket.app.state, "llm", None)
    if llm is None:
        llm = create_llm(settings)
        websocket.app.state.llm = llm
    return llm

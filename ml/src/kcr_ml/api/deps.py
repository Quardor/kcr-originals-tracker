"""FastAPI dependencies: principal, per-request data source, LLM and exporters.

kcr-ml sits behind kcr-backend and answers to it alone (§8): every protected
endpoint requires the internal service token, and the acting user is the one
kcr-backend asserts in the identity headers. The user's access token is
forwarded to kcr-backend verbatim, so data permissions are decided by the
backend and not duplicated here. The only public endpoint (health) does not
use these deps.
"""

from __future__ import annotations

import secrets
from collections.abc import AsyncIterator, Mapping
from datetime import date
from functools import lru_cache
from typing import Annotated

from fastapi import Depends, Request, WebSocket

from kcr_ml.application.ports import ExcelExporterPort, LlmPort
from kcr_ml.config import Settings
from kcr_ml.domain.criteria import ScoringWeights
from kcr_ml.domain.model import Principal
from kcr_ml.exceptions import AccessDeniedError, AppError, AuthenticationRequiredError, InternalTokenRejectedError
from kcr_ml.infrastructure.data.backend_api import BackendApiDataSource
from kcr_ml.infrastructure.excel.workbooks import OpenPyxlExporter
from kcr_ml.infrastructure.llm.factory import create_llm

INTERNAL_TOKEN_HEADER = "X-Internal-Token"
USER_ID_HEADER = "X-Kcr-User-Id"
USER_ROLE_HEADER = "X-Kcr-User-Role"
ROLES = ("ADMIN", "OPERATOR")


@lru_cache
def get_settings() -> Settings:
    """Application settings (single instance per process)."""
    return Settings()


def get_token(authorization: str | None) -> str:
    """Extract the bearer access token forwarded by kcr-backend."""
    if not authorization or not authorization.lower().startswith("bearer "):
        raise AuthenticationRequiredError("Missing bearer access token")
    return authorization[7:].strip()


def internal_token_accepted(internal_token: str | None, settings: Settings) -> bool:
    """Constant-time comparison of the service token; any input is safe to check."""
    return internal_token is not None and secrets.compare_digest(
        internal_token.encode("utf-8"), settings.auth_internal_token.encode("utf-8")
    )


def principal_from_headers(headers: Mapping[str, str], settings: Settings) -> Principal:
    """The acting user as asserted by kcr-backend; a foreign caller gets an error."""
    if not internal_token_accepted(headers.get(INTERNAL_TOKEN_HEADER), settings):
        raise InternalTokenRejectedError("Missing or invalid internal service token")
    user_id = headers.get(USER_ID_HEADER)
    role = headers.get(USER_ROLE_HEADER)
    if user_id is None or not user_id.isdigit() or role not in ROLES:
        raise InternalTokenRejectedError("Missing or invalid user identity headers")
    return Principal(user_id=int(user_id), role=role, token=get_token(headers.get("authorization")))


def require_internal_token(request: Request, settings: Annotated[Settings, Depends(get_settings)]) -> None:
    """Router-level guard: kcr-ml answers to kcr-backend only (§8)."""
    if not internal_token_accepted(request.headers.get(INTERNAL_TOKEN_HEADER), settings):
        raise InternalTokenRejectedError("Missing or invalid internal service token")


def get_principal(request: Request, settings: Annotated[Settings, Depends(get_settings)]) -> Principal:
    """Identity asserted by the authenticated kcr-backend request."""
    return principal_from_headers(request.headers, settings)


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
# WebSocket (kcr-backend opens the chat with regular HTTP headers, §8)
# --------------------------------------------------------------------------------------


def get_ws_token(websocket: WebSocket) -> str:
    """Access token from the Authorization header set by kcr-backend."""
    return websocket.headers.get("authorization", "")


def opened_by_backend(websocket: WebSocket, settings: Settings) -> bool:
    """True when the connection carries the service token and the asserted identity."""
    try:
        principal_from_headers(websocket.headers, settings)
    except AppError:
        return False
    return True


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

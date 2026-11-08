"""Application factory: routers, WebSocket, exception handlers, lifespan."""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from kcr_ml.api.deps import get_settings
from kcr_ml.api.routes import analytics, brief, export, health, prioritize
from kcr_ml.api.ws import chat as chat_ws
from kcr_ml.config import Settings
from kcr_ml.exceptions import AppError, ValidationFailedError
from kcr_ml.infrastructure.llm.factory import create_llm

logger = logging.getLogger(__name__)


def create_app(settings: Settings | None = None) -> FastAPI:
    """Build the FastAPI application; settings injectable for tests."""
    app_settings = settings or get_settings()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        app.state.llm = create_llm(app_settings)
        try:
            yield
        finally:
            await app.state.llm.aclose()

    app = FastAPI(title="kcr-ml", version="0.1.0", lifespan=lifespan)
    app.state.settings = app_settings

    app.add_middleware(
        CORSMiddleware,
        allow_origins=app_settings.cors_origins,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )

    app.include_router(health.router)
    app.include_router(analytics.router)
    app.include_router(brief.router)
    app.include_router(prioritize.router)
    app.include_router(export.router)
    app.include_router(chat_ws.router)

    @app.exception_handler(AppError)
    async def _app_error_handler(_request: Request, error: AppError) -> JSONResponse:
        return JSONResponse(status_code=error.http_status, content=error.to_body())

    @app.exception_handler(RequestValidationError)
    async def _validation_error_handler(_request: Request, error: RequestValidationError) -> JSONResponse:
        details = "; ".join(
            f"{'.'.join(str(part) for part in item['loc'])}: {item['msg']}" for item in error.errors()[:5]
        )
        body = ValidationFailedError(details or "Request validation failed").to_body()
        return JSONResponse(status_code=400, content=body)

    @app.exception_handler(Exception)
    async def _unexpected_error_handler(request: Request, error: Exception) -> JSONResponse:
        logger.exception("Unhandled error on %s %s", request.method, request.url.path)
        return JSONResponse(
            status_code=500,
            content={"code": "INTERNAL_ERROR", "message": "Internal server error"},
        )

    return app


app = create_app()

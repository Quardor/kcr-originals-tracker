"""Application errors and the unified error contract (ARCHITECTURE.md §7).

Every error reaching a client has the shape ``{"code", "message", "timestamp"}``.
Domain exceptions are mapped to HTTP statuses by a single handler in ``api``;
anything unexpected falls back to a neutral 500 INTERNAL_ERROR body.
"""

from __future__ import annotations

from datetime import UTC, datetime


class AppError(Exception):
    """Base class for application errors with a stable machine-readable code."""

    code = "INTERNAL_ERROR"
    http_status = 500

    def __init__(self, message: str = "") -> None:
        super().__init__(message or self.code)
        self.message = message or self.code

    def to_body(self) -> dict[str, str]:
        """REST/WS error payload per the shared contract."""
        return {
            "code": self.code,
            "message": self.message,
            "timestamp": datetime.now(UTC).isoformat(),
        }


class AuthenticationRequiredError(AppError):
    code = "AUTHENTICATION_REQUIRED"
    http_status = 401


class AccessDeniedError(AppError):
    code = "ACCESS_DENIED"
    http_status = 403


class ResourceNotFoundError(AppError):
    code = "RESOURCE_NOT_FOUND"
    http_status = 404


class ValidationFailedError(AppError):
    code = "VALIDATION_FAILED"
    http_status = 400


class BackendUnavailableError(AppError):
    code = "BACKEND_UNAVAILABLE"
    http_status = 502


class LlmTimeoutError(AppError):
    code = "LLM_TIMEOUT"
    http_status = 504


class LlmUnavailableError(AppError):
    code = "LLM_UNAVAILABLE"
    http_status = 502


class LlmInvalidResponseError(AppError):
    code = "LLM_INVALID_RESPONSE"
    http_status = 502


class ToolLoopLimitExceededError(AppError):
    code = "TOOL_LIMIT_EXCEEDED"
    http_status = 502

"""Application settings (pydantic-settings, ARCHITECTURE.md §13).

All configuration comes from the environment (optionally ``.env``); the service
refuses to start on invalid or missing mandatory values (fail-fast, §9).
"""

from __future__ import annotations

from typing import Literal

from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Strongly-typed settings; field names map 1:1 to environment variables."""

    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    # kcr-backend
    kcr_backend_base_url: str
    auth_jwt_secret: str

    # LLM provider (OpenAI-compatible for both modes)
    llm_provider: Literal["api", "local"]
    llm_base_url: str
    llm_api_key: str | None = None
    llm_model: str
    llm_timeout_seconds: float = 60
    llm_max_tokens: int = 2048

    # AI behaviour limits
    ai_chat_history_limit: int = 20
    ai_max_tool_iterations: int = 5
    ai_max_table_rows: int = 50
    ai_max_scan_clients: int = 500

    # Deterministic prioritization scoring weights (UC-3)
    ai_score_untransferred: float = 40
    ai_score_dynamics: float = 25
    ai_score_urgency: float = 20
    ai_score_stage: float = 10
    ai_score_vip: float = 5

    # Delivery
    app_cors_allowed_origins: str = "http://localhost:5173"

    @model_validator(mode="after")
    def _validate_provider_requirements(self) -> Settings:
        if self.llm_provider == "api" and not (self.llm_api_key or "").strip():
            raise ValueError("LLM_API_KEY is required when LLM_PROVIDER=api")
        if self.ai_chat_history_limit < 1 or self.ai_max_tool_iterations < 1:
            raise ValueError("AI_CHAT_HISTORY_LIMIT and AI_MAX_TOOL_ITERATIONS must be >= 1")
        return self

    @property
    def cors_origins(self) -> list[str]:
        """Comma-separated origins split into a list."""
        return [origin.strip() for origin in self.app_cors_allowed_origins.split(",") if origin.strip()]

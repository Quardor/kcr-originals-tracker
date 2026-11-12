"""Choice of the LLM adapter by settings (§5).

Every OpenAI-compatible provider — cloud API or local Qwen behind vLLM/Ollama —
is served by the single ``OpenAiCompatLlm`` adapter; switching is pure
configuration. A provider with a different protocol gets a new adapter class
plus one line here; use-cases never change.
"""

from __future__ import annotations

from kcr_ml.config import Settings
from kcr_ml.infrastructure.llm.openai_compat import OpenAiCompatLlm

LOCAL_API_KEY_PLACEHOLDER = "local"


def create_llm(settings: Settings) -> OpenAiCompatLlm:
    """Build the LLM adapter from settings (provider distinction is credential-only)."""
    return OpenAiCompatLlm(
        base_url=settings.llm_base_url,
        api_key=settings.llm_api_key or LOCAL_API_KEY_PLACEHOLDER,
        model=settings.llm_model,
        timeout_seconds=settings.llm_timeout_seconds,
        max_tokens=settings.llm_max_tokens,
    )

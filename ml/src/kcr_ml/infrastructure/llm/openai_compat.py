"""OpenAI-compatible LLM adapter — one implementation for cloud API and local Qwen.

Both modes (``LLM_PROVIDER=api`` and ``local`` via vLLM/Ollama) speak the
``/v1/chat/completions`` protocol with streaming and tool calling; the
difference is only in base URL and credentials (§5). A new provider with a
different protocol would be a new LlmPort adapter, not a change here.
"""

from __future__ import annotations

import json
import logging
from collections.abc import AsyncIterator, Mapping, Sequence

from openai import APIConnectionError, APIStatusError, APITimeoutError, AsyncOpenAI

from kcr_ml.application.ports import LlmEvent, LlmMessage, StreamDone, TextDelta, ToolCall, ToolSpec
from kcr_ml.exceptions import LlmInvalidResponseError, LlmTimeoutError, LlmUnavailableError

logger = logging.getLogger(__name__)


class OpenAiCompatLlm:
    """Streaming chat completions with tool calling over the OpenAI protocol."""

    def __init__(
        self,
        *,
        base_url: str,
        api_key: str,
        model: str,
        timeout_seconds: float,
        max_tokens: int,
    ) -> None:
        self._client = AsyncOpenAI(
            base_url=base_url,
            api_key=api_key,
            timeout=timeout_seconds,
            max_retries=1,
        )
        self._model = model
        self._max_tokens = max_tokens

    async def aclose(self) -> None:
        await self._client.close()

    async def chat_stream(
        self,
        messages: Sequence[LlmMessage],
        tools: Sequence[ToolSpec] = (),
    ) -> AsyncIterator[LlmEvent]:
        """Yield TextDelta chunks, then ToolCall events, then one StreamDone."""
        request_tools = [
            {
                "type": "function",
                "function": {
                    "name": tool.name,
                    "description": tool.description,
                    "parameters": tool.parameters,
                },
            }
            for tool in tools
        ]
        try:
            stream = await self._client.chat.completions.create(
                model=self._model,
                messages=[self._serialize_message(message) for message in messages],
                tools=request_tools or None,
                stream=True,
                max_tokens=self._max_tokens,
                stream_options={"include_usage": True},
            )
        except APITimeoutError as error:
            raise LlmTimeoutError("LLM provider did not respond in time") from error
        except (APIConnectionError, APIStatusError) as error:
            logger.warning("LLM provider error: %s", error.__class__.__name__)
            raise LlmUnavailableError("LLM provider is unavailable") from error

        tool_accumulator: dict[int, dict[str, str]] = {}
        finish_reason = "stop"
        total_tokens: int | None = None
        try:
            async for chunk in stream:
                if chunk.usage is not None:
                    total_tokens = chunk.usage.total_tokens
                for choice in chunk.choices or ():
                    delta = choice.delta
                    if delta is not None and delta.content:
                        yield TextDelta(delta.content)
                    for fragment in (delta.tool_calls if delta is not None else None) or ():
                        slot = tool_accumulator.setdefault(fragment.index, {"id": "", "name": "", "arguments": ""})
                        if fragment.id:
                            slot["id"] = fragment.id
                        if fragment.function is not None:
                            if fragment.function.name:
                                slot["name"] = fragment.function.name
                            if fragment.function.arguments:
                                slot["arguments"] += fragment.function.arguments
                    if choice.finish_reason:
                        finish_reason = choice.finish_reason
        except APITimeoutError as error:
            raise LlmTimeoutError("LLM provider did not respond in time") from error
        except (APIConnectionError, APIStatusError) as error:
            logger.warning("LLM provider stream error: %s", error.__class__.__name__)
            raise LlmUnavailableError("LLM provider stream failed") from error

        for index in sorted(tool_accumulator):
            slot = tool_accumulator[index]
            try:
                arguments = json.loads(slot["arguments"]) if slot["arguments"] else {}
            except json.JSONDecodeError as error:
                raise LlmInvalidResponseError(f"LLM returned malformed tool arguments for '{slot['name']}'") from error
            yield ToolCall(id=slot["id"] or f"call_{index}", name=slot["name"], arguments=arguments)
        yield StreamDone(finish_reason=finish_reason, total_tokens=total_tokens)

    @staticmethod
    def _serialize_message(message: Mapping[str, object]) -> dict[str, object]:
        """Pass port messages through; only re-serialize tool arguments to a string."""
        serialized = dict(message)
        tool_calls = serialized.get("tool_calls")
        if isinstance(tool_calls, list):
            serialized["tool_calls"] = [
                {
                    **dict(call),
                    "function": {
                        **dict(call["function"]),
                        "arguments": call["function"]["arguments"]
                        if isinstance(call["function"]["arguments"], str)
                        else json.dumps(call["function"]["arguments"], ensure_ascii=False, default=str),
                    },
                }
                for call in tool_calls
            ]
        return serialized

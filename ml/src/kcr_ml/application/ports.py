"""Ports of the application layer (ARCHITECTURE.md §3, §10).

Use-cases depend on these Protocols only; adapters in ``infrastructure/``
implement them. The read-only invariant is enforced here: ``DataSourcePort``
exposes reading methods only — no mutation path exists on any layer.
"""

from __future__ import annotations

from collections.abc import AsyncIterator, Mapping, Sequence
from dataclasses import dataclass, replace
from typing import Protocol

from kcr_ml.domain.model import (
    ClaimView,
    ClientQuery,
    ClientView,
    Page,
    SnapshotView,
    TabularData,
)

# --------------------------------------------------------------------------------------
# LLM port
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class ToolSpec:
    """Declarative description of one tool exposed to the LLM."""

    name: str
    description: str
    parameters: dict[str, object]  # JSON Schema of the arguments object


@dataclass(frozen=True, slots=True)
class TextDelta:
    """A chunk of generated text."""

    text: str


@dataclass(frozen=True, slots=True)
class ToolCall:
    """Model request to invoke a tool."""

    id: str
    name: str
    arguments: dict[str, object]


@dataclass(frozen=True, slots=True)
class StreamDone:
    """End of one completion: finish reason and (when reported) total tokens."""

    finish_reason: str
    total_tokens: int | None


LlmEvent = TextDelta | ToolCall | StreamDone

# OpenAI-style message dicts: {"role": "...", "content": ...}; tool results use
# role "tool" with "tool_call_id". This dict shape is the universal port contract.
LlmMessage = Mapping[str, object]


class LlmPort(Protocol):
    """Unified streaming + tool-calling contract for every use-case and provider."""

    def chat_stream(
        self,
        messages: Sequence[LlmMessage],
        tools: Sequence[ToolSpec] = (),
    ) -> AsyncIterator[LlmEvent]:
        """Stream one completion; tool calls arrive as ToolCall events."""
        ...


# --------------------------------------------------------------------------------------
# Data source port (read-only by design)
# --------------------------------------------------------------------------------------


class DataSourcePort(Protocol):
    """Read-only view of kcr-backend data, executed on behalf of the current user."""

    async def list_clients(self, query: ClientQuery) -> Page: ...
    async def get_client(self, client_id: int) -> ClientView | None: ...
    async def list_snapshots(self, client_id: int, reporting_period: str | None = None) -> list[SnapshotView]: ...
    async def list_events(self, client_id: int, page: int = 0, size: int = 20) -> Page: ...
    async def list_claims(self, client_id: int) -> list[ClaimView]: ...
    async def today_worklist(self, page: int = 0, size: int = 20) -> Page: ...


async def iter_all_clients(
    source: DataSourcePort,
    query: ClientQuery | None = None,
    max_clients: int = 500,
) -> list[ClientView]:
    """Page through the whole client directory (bounded by ``max_clients``)."""
    base = query or ClientQuery(size=100)
    collected: list[ClientView] = []
    current = replace(base, page=0, size=100)
    while True:
        result = await source.list_clients(current)
        collected.extend(item for item in result.content if isinstance(item, ClientView))
        if len(collected) >= max_clients or current.page + 1 >= result.total_pages:
            break
        current = current.next_page()
    return collected[:max_clients]


# --------------------------------------------------------------------------------------
# Excel export port
# --------------------------------------------------------------------------------------


class ExcelExporterPort(Protocol):
    """Render one or more tables into an xlsx workbook."""

    def export(self, tables: Sequence[TabularData]) -> bytes: ...

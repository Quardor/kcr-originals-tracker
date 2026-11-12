"""UC-1: chat use-case — in-memory history, tool-calling loop, streaming events.

Tools are reading methods of ``DataSourcePort`` described declaratively; results
are returned to the LLM verbatim (as JSON) and, when tabular, additionally sent
to the client as a ``data`` message. The LLM never gets HTTP, SQL or mutation
paths — the read-only invariant holds here too.
"""

from __future__ import annotations

import json
import uuid
from collections.abc import AsyncIterator, Awaitable, Callable
from dataclasses import dataclass
from datetime import date
from typing import Any

from pydantic import BaseModel, Field, ValidationError

from kcr_ml.application.ports import (
    DataSourcePort,
    LlmMessage,
    LlmPort,
    StreamDone,
    TabularData,
    TextDelta,
    ToolCall,
    ToolSpec,
)
from kcr_ml.domain.model import (
    ClientQuery,
    ClientType,
    ClientView,
    EventView,
    Page,
    SnapshotView,
    WorkStage,
)
from kcr_ml.domain.prompts import CHAT_SYSTEM_PROMPT
from kcr_ml.exceptions import ToolLoopLimitExceededError

# --------------------------------------------------------------------------------------
# Outgoing chat events (the WS layer serializes them, §7)
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class DeltaEvent:
    content: str


@dataclass(frozen=True, slots=True)
class ToolStatusEvent:
    tool: str


@dataclass(frozen=True, slots=True)
class DataEvent:
    table: TabularData


@dataclass(frozen=True, slots=True)
class DoneEvent:
    message_id: str
    total_tokens: int | None


ChatEvent = DeltaEvent | ToolStatusEvent | DataEvent | DoneEvent

# --------------------------------------------------------------------------------------
# Tools — thin wrappers over DataSourcePort
# --------------------------------------------------------------------------------------


@dataclass(frozen=True, slots=True)
class ToolResult:
    """Tool outcome: text content for the LLM and an optional table for the user."""

    content: str
    data: TabularData | None = None


class SearchClientsArgs(BaseModel):
    search: str | None = None
    client_type: str | None = None
    stage: str | None = None
    unassigned: bool | None = None
    page: int = Field(default=0, ge=0, le=1000)
    size: int = Field(default=20, ge=1, le=100)


class ClientIdArgs(BaseModel):
    client_id: int


class EventsArgs(BaseModel):
    client_id: int
    limit: int = Field(default=20, ge=1, le=100)


def _clients_table(clients: list[ClientView]) -> TabularData:
    from kcr_ml.domain.model import Column

    rows = [
        (
            client.kcr_code,
            client.organization_name,
            client.client_type.value if client.client_type else None,
            client.stage.value,
            client.assignee_username,
            client.next_contact_at.isoformat() if client.next_contact_at else None,
        )
        for client in clients
    ]
    return TabularData(
        title="Клиенты",
        columns=(
            Column("kcr_code", "Код KCR"),
            Column("organization_name", "Организация"),
            Column("client_type", "Тип"),
            Column("stage", "Этап"),
            Column("assignee", "Ответственный"),
            Column("next_contact_at", "След. контакт"),
        ),
        rows=tuple(rows),
    )


def _clients_content(clients: list[ClientView], total: int) -> str:
    payload = {
        "total": total,
        "clients": [
            {
                "id": client.id,
                "kcr_code": client.kcr_code,
                "organization_name": client.organization_name,
                "client_type": client.client_type.value if client.client_type else None,
                "stage": client.stage.value,
                "assignee": client.assignee_username,
                "next_contact_at": client.next_contact_at.isoformat() if client.next_contact_at else None,
            }
            for client in clients
        ],
    }
    return json.dumps(payload, ensure_ascii=False)


async def _tool_search_clients(source: DataSourcePort, args: SearchClientsArgs) -> ToolResult:
    query = ClientQuery(
        search=args.search,
        client_type=ClientType(args.client_type) if args.client_type else None,
        stage=WorkStage(args.stage) if args.stage else None,
        unassigned=args.unassigned,
        page=args.page,
        size=args.size,
    )
    result: Page = await source.list_clients(query)
    clients = [item for item in result.content if isinstance(item, ClientView)]
    return ToolResult(_clients_content(clients, result.total_elements), _clients_table(clients))


async def _tool_get_client(source: DataSourcePort, args: ClientIdArgs) -> ToolResult:
    client = await source.get_client(args.client_id)
    if client is None:
        return ToolResult(json.dumps({"error": f"client {args.client_id} not found"}, ensure_ascii=False))
    payload = {
        "id": client.id,
        "kcr_code": client.kcr_code,
        "organization_name": client.organization_name,
        "inn": client.inn,
        "client_type": client.client_type.value if client.client_type else None,
        "stage": client.stage.value,
        "assignee": client.assignee_username,
        "next_contact_at": client.next_contact_at.isoformat() if client.next_contact_at else None,
        "contact_person": client.contact_person,
        "contact_phone": client.contact_phone,
        "contact_email": client.contact_email,
        "manager": client.manager,
        "created_at": client.created_at.date().isoformat() if client.created_at else None,
    }
    return ToolResult(json.dumps(payload, ensure_ascii=False))


async def _tool_get_client_snapshots(source: DataSourcePort, args: ClientIdArgs) -> ToolResult:
    from kcr_ml.domain.model import Column

    snapshots: list[SnapshotView] = await source.list_snapshots(args.client_id)
    rows = [
        (
            snapshot.reporting_period,
            snapshot.snapshot_date.isoformat(),
            snapshot.total_documents,
            snapshot.not_transferred_documents,
            snapshot.untransferred_percent,
        )
        for snapshot in snapshots
    ]
    table = TabularData(
        title="Срезы передачи документов",
        columns=(
            Column("reporting_period", "Период"),
            Column("snapshot_date", "Дата среза"),
            Column("total_documents", "Всего документов"),
            Column("not_transferred_documents", "Непередано"),
            Column("percent", "% непередано"),
        ),
        rows=tuple(rows),
    )
    return ToolResult(json.dumps(table.as_dicts(), ensure_ascii=False), table)


async def _tool_get_client_events(source: DataSourcePort, args: EventsArgs) -> ToolResult:
    from kcr_ml.domain.model import Column

    page: Page = await source.list_events(args.client_id, page=0, size=args.limit)
    events = [item for item in page.content if isinstance(item, EventView)]
    rows = [
        (
            event.created_at.date().isoformat(),
            event.type.value,
            event.author_username,
            event.call_result.value if event.call_result else None,
            event.agreement,
            event.comment,
        )
        for event in events
    ]
    table = TabularData(
        title="События клиента",
        columns=(
            Column("date", "Дата"),
            Column("type", "Тип"),
            Column("author", "Автор"),
            Column("call_result", "Результат звонка"),
            Column("agreement", "Договорённость"),
            Column("comment", "Комментарий"),
        ),
        rows=tuple(rows),
    )
    return ToolResult(json.dumps({"total": page.total_elements, "events": table.as_dicts()}, ensure_ascii=False), table)


async def _tool_get_client_claims(source: DataSourcePort, args: ClientIdArgs) -> ToolResult:
    from kcr_ml.domain.model import Column

    claims = await source.list_claims(args.client_id)
    rows = [
        (
            claim.number,
            claim.claim_date.isoformat(),
            claim.status.value,
            "; ".join(f"{s.name} ({s.status.value})" for s in claim.sanctions) or None,
        )
        for claim in claims
    ]
    table = TabularData(
        title="Претензии",
        columns=(
            Column("number", "Номер"),
            Column("claim_date", "Дата"),
            Column("status", "Статус"),
            Column("sanctions", "Санкции"),
        ),
        rows=tuple(rows),
    )
    return ToolResult(json.dumps(table.as_dicts(), ensure_ascii=False), table)


async def _tool_get_today_clients(source: DataSourcePort, args: None) -> ToolResult:
    page: Page = await source.today_worklist(page=0, size=100)
    clients = [item for item in page.content if isinstance(item, ClientView)]
    return ToolResult(_clients_content(clients, page.total_elements), _clients_table(clients))


@dataclass(frozen=True, slots=True)
class ChatTool:
    """One tool exposed to the LLM: spec, args model and DataSourcePort handler."""

    spec: ToolSpec
    args_model: type[BaseModel] | None
    handler: Callable[[DataSourcePort, Any], Awaitable[ToolResult]]


CHAT_TOOLS: tuple[ChatTool, ...] = (
    ChatTool(
        spec=ToolSpec(
            name="search_clients",
            description=(
                "Поиск клиентов по фильтрам: подстрока в коде KCR или ИНН, тип клиента, этап, без ответственного."
            ),
            parameters={
                "type": "object",
                "properties": {
                    "search": {"type": "string", "description": "подстрока в коде KCR или ИНН"},
                    "client_type": {"type": "string", "enum": [t.value for t in ClientType]},
                    "stage": {"type": "string", "enum": [s.value for s in WorkStage]},
                    "unassigned": {"type": "boolean"},
                    "page": {"type": "integer", "minimum": 0},
                    "size": {"type": "integer", "minimum": 1, "maximum": 100},
                },
            },
        ),
        args_model=SearchClientsArgs,
        handler=_tool_search_clients,  # type: ignore[arg-type]
    ),
    ChatTool(
        spec=ToolSpec(
            name="get_client",
            description="Карточка клиента по внутреннему id: контакты, тип, этап, ответственный.",
            parameters={
                "type": "object",
                "properties": {"client_id": {"type": "integer"}},
                "required": ["client_id"],
            },
        ),
        args_model=ClientIdArgs,
        handler=_tool_get_client,  # type: ignore[arg-type]
    ),
    ChatTool(
        spec=ToolSpec(
            name="get_client_snapshots",
            description="История срезов передачи документов клиента (период, всего, непередано, %).",
            parameters={
                "type": "object",
                "properties": {"client_id": {"type": "integer"}},
                "required": ["client_id"],
            },
        ),
        args_model=ClientIdArgs,
        handler=_tool_get_client_snapshots,  # type: ignore[arg-type]
    ),
    ChatTool(
        spec=ToolSpec(
            name="get_client_events",
            description="События клиента от новых к старым: звонки (результат, договорённость), письма, комментарии.",
            parameters={
                "type": "object",
                "properties": {
                    "client_id": {"type": "integer"},
                    "limit": {"type": "integer", "minimum": 1, "maximum": 100},
                },
                "required": ["client_id"],
            },
        ),
        args_model=EventsArgs,
        handler=_tool_get_client_events,  # type: ignore[arg-type]
    ),
    ChatTool(
        spec=ToolSpec(
            name="get_client_claims",
            description="Претензии клиента и наложенные санкции.",
            parameters={
                "type": "object",
                "properties": {"client_id": {"type": "integer"}},
                "required": ["client_id"],
            },
        ),
        args_model=ClientIdArgs,
        handler=_tool_get_client_claims,  # type: ignore[arg-type]
    ),
    ChatTool(
        spec=ToolSpec(
            name="get_today_clients",
            description=("Клиенты, которым сегодня требуется внимание: контакт просрочен или назначен на сегодня."),
            parameters={"type": "object", "properties": {}},
        ),
        args_model=None,
        handler=_tool_get_today_clients,  # type: ignore[arg-type]
    ),
)

_TOOLS_BY_NAME = {tool.spec.name: tool for tool in CHAT_TOOLS}


class ChatSession:
    """One WebSocket connection: bounded history + tool-calling loop."""

    def __init__(
        self,
        source: DataSourcePort,
        llm: LlmPort,
        *,
        today: date,
        history_limit: int,
        max_tool_iterations: int,
        max_table_rows: int,
    ) -> None:
        self._source = source
        self._llm = llm
        self._today = today
        self._history_limit = history_limit
        self._max_tool_iterations = max_tool_iterations
        self._max_table_rows = max_table_rows
        self._history: list[LlmMessage] = []

    async def handle(self, user_text: str) -> AsyncIterator[ChatEvent]:
        """Process one user message; stream deltas, tool statuses, data and done."""
        system = {"role": "system", "content": f"{CHAT_SYSTEM_PROMPT}\n\nСегодня: {self._today.isoformat()}."}
        working: list[LlmMessage] = [
            system,
            *self._history,
            {"role": "user", "content": user_text},
        ]
        tools = tuple(tool.spec for tool in CHAT_TOOLS)

        for _iteration in range(self._max_tool_iterations + 1):
            turn_text: list[str] = []
            tool_calls: list[ToolCall] = []
            total_tokens: int | None = None
            async for event in self._llm.chat_stream(working, tools):
                if isinstance(event, TextDelta):
                    turn_text.append(event.text)
                    yield DeltaEvent(event.text)
                elif isinstance(event, ToolCall):
                    tool_calls.append(event)
                elif isinstance(event, StreamDone):
                    total_tokens = event.total_tokens

            if not tool_calls:
                self._remember(user_text, "".join(turn_text))
                yield DoneEvent(uuid.uuid4().hex[:12], total_tokens)
                return

            if _iteration == self._max_tool_iterations:
                raise ToolLoopLimitExceededError(
                    f"Tool loop exceeded {self._max_tool_iterations} iterations without a final answer"
                )

            working.append(self._assistant_tool_calls_message(tool_calls, "".join(turn_text)))
            for call in tool_calls:
                yield ToolStatusEvent(call.name)
                result = await self._execute(call)
                working.append({"role": "tool", "tool_call_id": call.id, "content": result.content})
                if result.data is not None:
                    yield DataEvent(result.data.limited(self._max_table_rows))

    def _remember(self, user_text: str, assistant_text: str) -> None:
        self._history.append({"role": "user", "content": user_text})
        self._history.append({"role": "assistant", "content": assistant_text})
        self._history = self._history[-self._history_limit :]

    async def _execute(self, call: ToolCall) -> ToolResult:
        """Run one tool call; invalid arguments become a recoverable error payload."""
        tool = _TOOLS_BY_NAME.get(call.name)
        if tool is None:
            return ToolResult(json.dumps({"error": f"unknown tool '{call.name}'"}, ensure_ascii=False))
        try:
            if tool.args_model is None:
                handler = tool.handler
                return await handler(self._source, None)
            parsed = tool.args_model.model_validate(call.arguments)
            return await tool.handler(self._source, parsed)
        except ValidationError as error:
            return ToolResult(
                json.dumps({"error": f"invalid arguments: {error.error_count()} errors"}, ensure_ascii=False)
            )

    @staticmethod
    def _assistant_tool_calls_message(calls: list[ToolCall], text: str) -> LlmMessage:
        return {
            "role": "assistant",
            "content": text or None,
            "tool_calls": [
                {
                    "id": call.id,
                    "type": "function",
                    "function": {
                        "name": call.name,
                        "arguments": json.dumps(call.arguments, ensure_ascii=False, default=str),
                    },
                }
                for call in calls
            ],
        }

"""Unit tests of the chat session (UC-1): tool loop, streaming, limits, refusals."""

from __future__ import annotations

import json

import pytest

from kcr_ml.application.chat_service import (
    ChatSession,
    DataEvent,
    DeltaEvent,
    DoneEvent,
    ToolStatusEvent,
)
from kcr_ml.application.ports import StreamDone, ToolCall
from kcr_ml.exceptions import ToolLoopLimitExceededError
from tests.fakes import TODAY, FakeDataSource, ScriptedLlm, text_script


def make_session(source: FakeDataSource, llm: ScriptedLlm, **kwargs: object) -> ChatSession:
    defaults: dict[str, object] = {
        "today": TODAY,
        "history_limit": 20,
        "max_tool_iterations": 5,
        "max_table_rows": 50,
    }
    defaults.update(kwargs)
    return ChatSession(source, llm, **defaults)  # type: ignore[arg-type]


async def collect(session: ChatSession, text: str) -> list[object]:
    return [event async for event in session.handle(text)]


def tool_call(name: str, arguments: dict[str, object], call_id: str = "call_1") -> ToolCall:
    return ToolCall(id=call_id, name=name, arguments=arguments)


async def test_tool_loop_streams_statuses_data_and_done() -> None:
    source = FakeDataSource(today=TODAY)
    llm = ScriptedLlm(
        [
            [tool_call("search_clients", {"size": 5}), StreamDone("tool_calls", None)],
            text_script("В таблице клиенты с просроченным контактом.", tokens=42),
        ]
    )
    events = await collect(make_session(source, llm), "Кому пора позвонить?")
    kinds = [type(e) for e in events]
    assert kinds == [ToolStatusEvent, DataEvent, DeltaEvent, DoneEvent]
    data = events[1]
    assert data.table.title == "Клиенты"  # type: ignore[attr-defined]
    assert data.table.columns[0].label == "Код KCR"  # type: ignore[attr-defined]
    assert len(data.table.rows) == 5  # type: ignore[attr-defined]
    assert events[3].total_tokens == 42  # type: ignore[attr-defined]
    assert "list_clients" in source.calls


async def test_data_rows_capped_by_limit() -> None:
    source = FakeDataSource(today=TODAY)
    llm = ScriptedLlm(
        [
            [tool_call("get_client_snapshots", {"client_id": 10}), StreamDone("tool_calls", None)],
            text_script("Долг держится."),
        ]
    )
    events = await collect(make_session(source, llm, max_table_rows=2), "Динамика Ладоги")
    data = next(e for e in events if isinstance(e, DataEvent))
    assert len(data.table.rows) == 2
    assert "первые 2 из 4" in data.table.title


async def test_invalid_tool_arguments_are_recoverable() -> None:
    source = FakeDataSource(today=TODAY)
    llm = ScriptedLlm(
        [
            [tool_call("get_client", {"client_id": "abc"}), StreamDone("tool_calls", None)],
            text_script("Уточню id и попробую снова."),
        ]
    )
    events = await collect(make_session(source, llm), "карточка")
    assert isinstance(events[-1], DoneEvent)
    # the tool result fed back to the LLM mentions the error
    tool_message = llm.calls[1]["messages"][-1]
    assert "invalid arguments" in tool_message["content"]


async def test_unknown_tool_returns_error_payload_not_crash() -> None:
    source = FakeDataSource(today=TODAY)
    llm = ScriptedLlm(
        [
            [tool_call("delete_client", {"id": 1}), StreamDone("tool_calls", None)],
            text_script("Такого инструмента нет — я только читаю данные."),
        ]
    )
    events = await collect(make_session(source, llm), "удали клиента")
    assert isinstance(events[-1], DoneEvent)
    tool_message = llm.calls[1]["messages"][-1]
    assert "unknown tool" in tool_message["content"]


async def test_iteration_limit_raises_controlled_error() -> None:
    source = FakeDataSource(today=TODAY)
    scripts = [
        [tool_call("get_client", {"client_id": 1}), StreamDone("tool_calls", None)],
    ] * 3
    llm = ScriptedLlm(scripts)
    with pytest.raises(ToolLoopLimitExceededError):
        await collect(make_session(source, llm, max_tool_iterations=2), "вопрос")


async def test_history_is_trimmed_between_turns() -> None:
    source = FakeDataSource(today=TODAY)
    llm = ScriptedLlm(
        [
            text_script("Ответ один."),
            text_script("Ответ два."),
            text_script("Ответ три."),
        ]
    )
    session = make_session(source, llm, history_limit=2)
    await collect(session, "вопрос 1")
    await collect(session, "вопрос 2")
    await collect(session, "вопрос 3")
    # system + 2 history messages + current user message
    messages = llm.calls[2]["messages"]
    assert len(messages) == 4
    assert messages[1]["content"] == "вопрос 2"
    assert messages[-1]["content"] == "вопрос 3"


async def test_refusal_answer_without_tool_calls() -> None:
    source = FakeDataSource(today=TODAY)
    llm = ScriptedLlm(
        [
            text_script("Я не изменяю данные — решение принимает оператор в основном интерфейсе."),
        ]
    )
    events = await collect(make_session(source, llm), "переведи клиента на этап Претензия")
    assert [type(e) for e in events] == [DeltaEvent, DoneEvent]
    assert source.calls == []  # nothing was even read — pure refusal


async def test_tool_result_carries_source_numbers() -> None:
    source = FakeDataSource(today=TODAY)
    llm = ScriptedLlm(
        [
            [tool_call("get_client_snapshots", {"client_id": 1}), StreamDone("tool_calls", None)],
            text_script("Долг 45%."),
        ]
    )
    await collect(make_session(source, llm), "динамика Альфы")
    tool_message = llm.calls[1]["messages"][-1]
    payload = json.loads(tool_message["content"])
    assert payload[-1]["not_transferred_documents"] == 45  # type: ignore[index]

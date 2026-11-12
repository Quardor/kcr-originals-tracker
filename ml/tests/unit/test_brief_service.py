"""Unit tests of the brief use-case (UC-2): facts from data, wording from LLM."""

from __future__ import annotations

import pytest

from kcr_ml.application.brief_service import build_brief
from kcr_ml.exceptions import ResourceNotFoundError
from tests.fakes import TODAY, FakeDataSource, ScriptedLlm, text_script


@pytest.fixture
def source() -> FakeDataSource:
    return FakeDataSource(today=TODAY)


async def test_brief_combines_facts_and_llm_summary(source: FakeDataSource) -> None:
    llm = ScriptedLlm([text_script("Сводка: клиент с долгом, нужен звонок.")])
    brief = await build_brief(source, llm, client_id=2)
    assert brief.summary == "Сводка: клиент с долгом, нужен звонок."
    assert brief.client.kcr_code == "kcr_0002"
    assert brief.dynamics_category == "REAPPEARED"
    # facts carry only backend numbers
    assert "непередано 10 из 50 (20%)" in brief.facts
    assert "Передаст оригиналы до 20 ноября" in brief.facts
    assert "П-101" in brief.facts
    assert brief.recent_events[0].type.value == "CALL"
    # the LLM call contains the deterministic facts block
    user_message = llm.calls[0]["messages"][1]
    assert "kcr_0002" in user_message["content"]


async def test_brief_facts_mark_missing_snapshots(source: FakeDataSource) -> None:
    llm = ScriptedLlm([text_script("Данных о передаче документов нет.")])
    brief = await build_brief(source, llm, client_id=9)
    assert "Срезы передачи документов: данных нет" in brief.facts
    assert brief.latest_snapshot is None
    assert brief.dynamics_category is None


async def test_brief_unknown_client_raises_404(source: FakeDataSource) -> None:
    llm = ScriptedLlm([])
    with pytest.raises(ResourceNotFoundError):
        await build_brief(source, llm, client_id=999)

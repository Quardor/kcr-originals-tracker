"""Unit tests of the prioritize use-case (UC-3): structured criteria + deterministic core."""

from __future__ import annotations

import json

import pytest

from kcr_ml.application.prioritize_service import prioritize
from kcr_ml.domain.criteria import ScoringWeights
from kcr_ml.exceptions import LlmInvalidResponseError
from tests.fakes import TODAY, FakeDataSource, ScriptedLlm, text_script

PROMPT = "Не VIP, непередано больше 25%, не в работе"

CRITERIA_JSON = json.dumps(
    {
        "exclude_client_types": ["VIP"],
        "min_untransferred_percent": 25,
        "in_work": False,
    }
)


@pytest.fixture
def source() -> FakeDataSource:
    return FakeDataSource(today=TODAY)


async def test_prioritize_full_flow(source: FakeDataSource) -> None:
    llm = ScriptedLlm(
        [
            text_script(CRITERIA_JSON),
            text_script("Вверху списка — клиенты с наибольшей долей непереданных."),
        ]
    )
    result = await prioritize(source, llm, PROMPT, limit=10, weights=ScoringWeights(), today=TODAY, max_clients=100)
    assert [item.client.kcr_code for item in result.items] == ["kcr_0001", "kcr_0010", "kcr_0006"]
    assert result.criteria.min_untransferred_percent == 25
    assert result.criteria.exclude_client_types == ["VIP"]
    assert result.explanation.startswith("Вверху списка")
    # second LLM call receives the computed list, not raw data
    explain_prompt = llm.calls[1]["messages"][1]["content"]
    assert "kcr_0001" in explain_prompt


async def test_criteria_retry_on_invalid_then_valid(source: FakeDataSource) -> None:
    llm = ScriptedLlm(
        [
            text_script("Вот ваши критерии: ..."),  # not JSON
            text_script(CRITERIA_JSON),
            text_script("Пояснение."),
        ]
    )
    result = await prioritize(source, llm, PROMPT, limit=5, weights=ScoringWeights(), today=TODAY, max_clients=100)
    assert len(result.items) == 3
    assert len(llm.calls) == 3  # criteria (x2) + explanation


async def test_criteria_code_fence_is_stripped(source: FakeDataSource) -> None:
    fenced = f"```json\n{CRITERIA_JSON}\n```"
    llm = ScriptedLlm([text_script(fenced), text_script("ok")])
    result = await prioritize(source, llm, PROMPT, limit=5, weights=ScoringWeights(), today=TODAY, max_clients=100)
    assert result.criteria.in_work is False


async def test_two_invalid_criteria_attempts_refuse(source: FakeDataSource) -> None:
    llm = ScriptedLlm([text_script("nope"), text_script("still not json")])
    with pytest.raises(LlmInvalidResponseError):
        await prioritize(source, llm, PROMPT, limit=5, weights=ScoringWeights(), today=TODAY, max_clients=100)
    assert len(llm.calls) == 2


async def test_unknown_enum_in_criteria_is_invalid(source: FakeDataSource) -> None:
    bad = json.dumps({"dynamics_categories": ["STALE"]})
    llm = ScriptedLlm([text_script(bad), text_script(bad)])
    with pytest.raises(LlmInvalidResponseError):
        await prioritize(source, llm, PROMPT, limit=5, weights=ScoringWeights(), today=TODAY, max_clients=100)


async def test_empty_result_skips_explanation_call(source: FakeDataSource) -> None:
    impossible = json.dumps(
        {"min_untransferred_percent": 99.9, "client_types": ["VIP"], "exclude_client_types": ["VIP"]}
    )
    llm = ScriptedLlm([text_script(impossible)])
    result = await prioritize(
        source, llm, "невозможный запрос", limit=5, weights=ScoringWeights(), today=TODAY, max_clients=100
    )
    assert result.items == []
    assert result.explanation == "По заданным критериям подходящие клиенты не найдены."
    assert len(llm.calls) == 1

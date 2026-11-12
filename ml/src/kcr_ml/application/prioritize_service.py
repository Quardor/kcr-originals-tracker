"""UC-3: priority selection — LLM structures the request, code filters and scores.

Anti-hallucination core (ARCHITECTURE.md §2): the LLM only converts free text
into ``ClientCriteria`` (validated Pydantic schema, one retry, then refusal);
filtering, scoring and ordering are deterministic (domain/criteria.py). The
explanation is worded by the LLM on top of the already computed list.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import date

from pydantic import BaseModel, Field, ValidationError

from kcr_ml.application.ports import DataSourcePort, LlmPort, TextDelta, ToolCall, iter_all_clients
from kcr_ml.domain.criteria import ClientCriteria, ScoredClient, ScoringWeights, rank
from kcr_ml.domain.model import ClientType, DynamicsCategory
from kcr_ml.domain.prompts import (
    PRIORITIZE_CRITERIA_PROMPT,
    PRIORITIZE_EXPLAIN_PROMPT,
    build_prioritize_explain_prompt,
)
from kcr_ml.exceptions import LlmInvalidResponseError

EXPLAIN_TOP_ITEMS = 10
VALID_CLIENT_TYPES = {item.value for item in ClientType}
VALID_CATEGORIES = {item.value for item in DynamicsCategory}


class PrioritizeCriteriaModel(BaseModel):
    """Structured output of the criteria call — the only LLM-shaped input of UC-3."""

    client_types: list[str] | None = None
    exclude_client_types: list[str] | None = None
    min_untransferred_percent: float | None = Field(default=None, ge=0, le=100)
    max_untransferred_percent: float | None = Field(default=None, ge=0, le=100)
    dynamics_categories: list[str] | None = None
    in_work: bool | None = None
    assigned: bool | None = None
    next_contact_overdue: bool | None = None
    has_debt: bool | None = None
    sort_direction: str = "desc"

    def to_domain(self) -> ClientCriteria:
        criteria = ClientCriteria(
            client_types=self.client_types,
            exclude_client_types=self.exclude_client_types,
            min_untransferred_percent=self.min_untransferred_percent,
            max_untransferred_percent=self.max_untransferred_percent,
            dynamics_categories=self.dynamics_categories,
            in_work=self.in_work,
            assigned=self.assigned,
            next_contact_overdue=self.next_contact_overdue,
            has_debt=self.has_debt,
            sort_direction=self.sort_direction,
        )
        return criteria.normalized()


@dataclass(frozen=True, slots=True)
class PrioritizeResult:
    """Criteria (machine-readable) + scored list + LLM explanation."""

    criteria: ClientCriteria
    items: list[ScoredClient]
    explanation: str


def _validate_semantics(criteria: PrioritizeCriteriaModel) -> None:
    """Unknown enum names or inverted bounds are invalid structured output."""
    if criteria.client_types and not set(criteria.client_types) <= VALID_CLIENT_TYPES:
        raise ValueError(f"unknown client types: {criteria.client_types}")
    if criteria.exclude_client_types and not set(criteria.exclude_client_types) <= VALID_CLIENT_TYPES:
        raise ValueError(f"unknown client types: {criteria.exclude_client_types}")
    if criteria.dynamics_categories and not set(criteria.dynamics_categories) <= VALID_CATEGORIES:
        raise ValueError(f"unknown dynamics categories: {criteria.dynamics_categories}")
    if (
        criteria.min_untransferred_percent is not None
        and criteria.max_untransferred_percent is not None
        and criteria.min_untransferred_percent > criteria.max_untransferred_percent
    ):
        raise ValueError("min_untransferred_percent must not exceed max_untransferred_percent")


def _strip_code_fence(text: str) -> str:
    stripped = text.strip()
    if stripped.startswith("```"):
        lines = stripped.splitlines()
        lines[0] = lines[0].removeprefix("```json").removeprefix("```").strip()
        if len(lines) > 1 and lines[-1].strip().endswith("```"):
            lines = lines[:-1]
        stripped = "\n".join(lines).strip()
    return stripped


async def _collect_text(llm: LlmPort, messages: list[dict[str, str]]) -> str:
    parts: list[str] = []
    async for event in llm.chat_stream(messages):
        if isinstance(event, TextDelta):
            parts.append(event.text)
        elif isinstance(event, ToolCall):
            raise LlmInvalidResponseError("Unexpected tool call where plain text was expected")
    return "".join(parts)


async def _extract_criteria(llm: LlmPort, prompt: str) -> ClientCriteria:
    """Free text → validated criteria; one retry on invalid output, then refusal."""
    messages = [
        {"role": "system", "content": PRIORITIZE_CRITERIA_PROMPT},
        {"role": "user", "content": prompt},
    ]
    last_error = "empty response"
    for _attempt in range(2):
        raw = await _collect_text(llm, messages)
        try:
            model = PrioritizeCriteriaModel.model_validate(json.loads(_strip_code_fence(raw)))
            _validate_semantics(model)
            return model.to_domain()
        except (json.JSONDecodeError, ValidationError, ValueError) as error:
            last_error = str(error)
            messages.append({"role": "assistant", "content": raw})
            messages.append(
                {
                    "role": "user",
                    "content": f"Невалидный ответ ({last_error}). Верни только корректный JSON по схеме.",
                }
            )
    raise LlmInvalidResponseError(f"LLM failed to produce valid criteria: {last_error}")


async def _explain(llm: LlmPort, criteria: ClientCriteria, items: list[ScoredClient]) -> str:
    if not items:
        return "По заданным критериям подходящие клиенты не найдены."
    top_lines = [
        f"{item.client.kcr_code} — {item.client.organization_name} — балл {item.score} — "
        f"{'; '.join(item.reasons) or 'без выраженных факторов'}"
        for item in items[:EXPLAIN_TOP_ITEMS]
    ]
    messages = [
        {"role": "system", "content": PRIORITIZE_EXPLAIN_PROMPT},
        {"role": "user", "content": build_prioritize_explain_prompt(criteria.describe(), "\n".join(top_lines))},
    ]
    return await _collect_text(llm, messages)


async def prioritize(
    source: DataSourcePort,
    llm: LlmPort,
    prompt: str,
    *,
    limit: int,
    weights: ScoringWeights,
    today: date,
    max_clients: int,
) -> PrioritizeResult:
    """Full UC-3 flow: criteria → deterministic ranking → explanation."""
    criteria = await _extract_criteria(llm, prompt)
    clients = await iter_all_clients(source, max_clients=max_clients)
    entries = [(client, await source.list_snapshots(client.id)) for client in clients]
    scored = rank(entries, criteria, today, weights, limit)
    explanation = await _explain(llm, criteria, scored)
    return PrioritizeResult(criteria=criteria, items=scored, explanation=explanation)

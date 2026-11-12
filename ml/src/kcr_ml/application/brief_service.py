"""UC-2: pre-call brief — deterministic facts from the backend + LLM-worded summary.

All numbers in the facts block come from the data source; the LLM only words
them. The deterministic part of the answer is returned alongside the summary so
the client can always display exact values.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date

from kcr_ml.application.ports import DataSourcePort, LlmPort, TextDelta, ToolCall
from kcr_ml.domain import dynamics
from kcr_ml.domain.model import ClaimView, ClientView, EventView, SnapshotView
from kcr_ml.domain.prompts import BRIEF_SYSTEM_PROMPT, build_brief_user_prompt
from kcr_ml.exceptions import LlmInvalidResponseError, ResourceNotFoundError

RECENT_EVENTS_LIMIT = 20

CATEGORY_DESCRIPTIONS = {
    "NO_DEBT": "все документы переданы",
    "FIRST_OCCURRENCE": "непереданные документы появились впервые",
    "DECREASING": "количество непереданных уменьшается",
    "UNCHANGED_OR_GROWING": "количество непереданных не уменьшается",
    "REAPPEARED": "долг вернулся после полного погашения",
    "PERSISTENT": "непереданные документы в нескольких периодах подряд",
    "NEWCOMER_RISK": "долг у новичка (подключение менее 6 месяцев назад)",
}


@dataclass(frozen=True, slots=True)
class BriefData:
    """Deterministic brief payload plus the LLM-worded summary text."""

    client: ClientView
    latest_snapshot: SnapshotView | None
    dynamics_category: str | None
    dynamics_description: str | None
    period_categories: tuple[tuple[str, str], ...]
    recent_events: list[EventView]
    claims: list[ClaimView]
    next_action_date: date | None
    facts: str
    summary: str


def build_facts(
    client: ClientView,
    snapshots: list[SnapshotView],
    events: list[EventView],
    claims: list[ClaimView],
) -> tuple[str, str | None, str | None, tuple[tuple[str, str], ...]]:
    """Compose the deterministic facts block; returns (facts, category, description, periods)."""
    created_on = client.created_at.date() if client.created_at else None
    history = dynamics.classify_history(snapshots, created_on)
    latest = dynamics.latest_snapshot(snapshots)
    category = history[-1].category if history else None
    description = CATEGORY_DESCRIPTIONS.get(category.value) if category else None

    lines: list[str] = []
    lines.append(f"Клиент: {client.organization_name}")
    lines.append(f"Код KCR: {client.kcr_code}, ИНН: {client.inn}")
    type_label = client.client_type.value if client.client_type else "не указан"
    lines.append(f"Тип клиента: {type_label}")
    lines.append(f"Этап работы: {client.stage.value}")
    assignee = client.assignee_username or "не назначен"
    lines.append(f"Ответственный: {assignee}")
    if client.next_contact_at is not None:
        lines.append(f"Следующий контакт: {client.next_contact_at.isoformat()}")
    if latest is not None:
        percent = latest.untransferred_percent
        percent_label = f" ({percent:g}%)" if percent is not None else ""
        lines.append(
            f"Последний срез ({latest.reporting_period}, {latest.snapshot_date.isoformat()}): "
            f"непередано {latest.not_transferred_documents} из {latest.total_documents or '—'}{percent_label}"
        )
    if category is not None:
        lines.append(f"Динамика: {category.value} — {description}")
    if history:
        periods = "; ".join(f"{pc.period}={pc.category.value}" for pc in history)
        lines.append(f"Категории по периодам: {periods}")
    if not snapshots:
        lines.append("Срезы передачи документов: данных нет")
    if events:
        lines.append("Последние события (от новых к старым):")
        for event in events[:RECENT_EVENTS_LIMIT]:
            parts = [f"{event.created_at.date().isoformat()} {event.type.value} ({event.author_username})"]
            if event.call_result is not None:
                parts.append(f"результат: {event.call_result.value}")
            if event.agreement:
                parts.append(f"договорённость: {event.agreement}")
            if event.client_deadline is not None:
                parts.append(f"срок клиента: {event.client_deadline.isoformat()}")
            if event.next_contact_at is not None:
                parts.append(f"назначен контакт: {event.next_contact_at.isoformat()}")
            if event.comment:
                parts.append(f"комментарий: {event.comment}")
            lines.append("- " + ", ".join(parts))
    else:
        lines.append("Событий нет")
    if claims:
        lines.append("Претензии:")
        for claim in claims:
            sanctions = ", ".join(f"{s.name} ({s.status.value})" for s in claim.sanctions) or "нет"
            lines.append(
                f"- №{claim.number} от {claim.claim_date.isoformat()}, "
                f"статус {claim.status.value}; санкции: {sanctions}"
            )
    return (
        "\n".join(lines),
        category.value if category else None,
        description,
        tuple((pc.period, pc.category.value) for pc in history),
    )


async def build_brief(source: DataSourcePort, llm: LlmPort, client_id: int) -> BriefData:
    """Collect data, compose facts and ask the LLM to word the summary."""
    client = await source.get_client(client_id)
    if client is None:
        raise ResourceNotFoundError(f"Client {client_id} not found")
    snapshots = await source.list_snapshots(client_id)
    events_page = await source.list_events(client_id, page=0, size=RECENT_EVENTS_LIMIT)
    events = [event for event in events_page.content if isinstance(event, EventView)]
    claims = await source.list_claims(client_id)

    facts, category, description, period_categories = build_facts(client, snapshots, events, claims)

    messages = [
        {"role": "system", "content": BRIEF_SYSTEM_PROMPT},
        {"role": "user", "content": build_brief_user_prompt(facts)},
    ]
    text_parts: list[str] = []
    async for event in llm.chat_stream(messages):
        if isinstance(event, TextDelta):
            text_parts.append(event.text)
        elif isinstance(event, ToolCall):  # tools are not offered for briefs
            raise LlmInvalidResponseError("Unexpected tool call in brief completion")
    return BriefData(
        client=client,
        latest_snapshot=dynamics.latest_snapshot(snapshots),
        dynamics_category=category,
        dynamics_description=description,
        period_categories=period_categories,
        recent_events=events,
        claims=claims,
        next_action_date=client.next_contact_at,
        facts=facts,
        summary="".join(text_parts),
    )

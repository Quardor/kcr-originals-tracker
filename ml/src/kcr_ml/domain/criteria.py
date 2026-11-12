"""Prioritization criteria and deterministic scoring (UC-3, ARCHITECTURE.md §2).

The LLM only structures a free-form request into ``ClientCriteria``; filtering
and scoring are performed by this module — pure, deterministic code. Scores and
justification reasons are always explainable and reproducible.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date

from kcr_ml.domain import dynamics
from kcr_ml.domain.model import ClientView, DynamicsCategory, SnapshotView, WorkStage

# Clients below this untransferred share are not pooled for calls, except newcomers
# (backend ARCHITECTURE.md §11.2).
POOL_MIN_PERCENT = 5.0

# Share of the dynamics weight granted per category (worst first).
DYNAMICS_CATEGORY_WEIGHT: dict[DynamicsCategory, float] = {
    DynamicsCategory.PERSISTENT: 1.0,
    DynamicsCategory.UNCHANGED_OR_GROWING: 0.9,
    DynamicsCategory.REAPPEARED: 0.85,
    DynamicsCategory.NEWCOMER_RISK: 0.8,
    DynamicsCategory.FIRST_OCCURRENCE: 0.6,
    DynamicsCategory.DECREASING: 0.4,
    DynamicsCategory.NO_DEBT: 0.0,
}

# Share of the stage weight granted per stage.
STAGE_WEIGHT: dict[WorkStage, float] = {
    WorkStage.FIRST_CALL: 1.0,
    WorkStage.SECOND_CALL: 0.9,
    WorkStage.CLAIM_WORK: 0.8,
    WorkStage.RECHECK: 0.7,
    WorkStage.WAITING_CLIENT_DEADLINE: 0.5,
    WorkStage.IN_POOL: 0.3,
    WorkStage.COMPLETED: 0.2,
    WorkStage.NOT_IN_WORK: 0.0,
}

URGENCY_CAP_DAYS = 14


@dataclass(frozen=True, slots=True)
class ScoringWeights:
    """Max points per factor; defaults favour debt share, then dynamics, urgency, stage, VIP."""

    untransferred: float = 40
    dynamics: float = 25
    urgency: float = 20
    stage: float = 10
    vip: float = 5


@dataclass(slots=True)
class ClientCriteria:
    """Structured selection criteria — the machine-readable part of a UC-3 answer.

    All fields are optional constraints combined with AND semantics.
    """

    client_types: list[str] | None = None
    exclude_client_types: list[str] | None = None
    min_untransferred_percent: float | None = None
    max_untransferred_percent: float | None = None
    dynamics_categories: list[str] | None = None
    in_work: bool | None = None
    assigned: bool | None = None
    next_contact_overdue: bool | None = None
    has_debt: bool | None = None
    sort_direction: str = "desc"

    def normalized(self) -> ClientCriteria:
        """Copy with enum names upper-cased and sort direction validated."""

        def upper(values: list[str] | None) -> list[str] | None:
            return [v.strip().upper() for v in values] if values else None

        direction = self.sort_direction.strip().lower()
        if direction not in ("asc", "desc"):
            raise ValueError(f"sort_direction must be asc or desc, got '{self.sort_direction}'")
        return ClientCriteria(
            client_types=upper(self.client_types),
            exclude_client_types=upper(self.exclude_client_types),
            min_untransferred_percent=self.min_untransferred_percent,
            max_untransferred_percent=self.max_untransferred_percent,
            dynamics_categories=upper(self.dynamics_categories),
            in_work=self.in_work,
            assigned=self.assigned,
            next_contact_overdue=self.next_contact_overdue,
            has_debt=self.has_debt,
            sort_direction=direction,
        )

    def describe(self) -> str:
        """Human-readable summary of active constraints."""
        parts: list[str] = []
        if self.client_types:
            parts.append("типы: " + ", ".join(self.client_types))
        if self.exclude_client_types:
            parts.append("исключить типы: " + ", ".join(self.exclude_client_types))
        if self.min_untransferred_percent is not None:
            parts.append(f"непередано ≥ {self.min_untransferred_percent}%")
        if self.max_untransferred_percent is not None:
            parts.append(f"непередано ≤ {self.max_untransferred_percent}%")
        if self.dynamics_categories:
            parts.append("динамика: " + ", ".join(self.dynamics_categories))
        if self.in_work is True:
            parts.append("в работе")
        if self.in_work is False:
            parts.append("не в работе")
        if self.assigned is True:
            parts.append("есть ответственный")
        if self.assigned is False:
            parts.append("без ответственного")
        if self.next_contact_overdue is True:
            parts.append("следующий контакт просрочен")
        if self.has_debt is True:
            parts.append("есть непереданные документы")
        if self.has_debt is False:
            parts.append("нет непереданных документов")
        return "; ".join(parts) if parts else "без ограничений"


@dataclass(frozen=True, slots=True)
class ScoredClient:
    """Client with a deterministic priority score and factor explanations."""

    client: ClientView
    score: int
    untransferred_percent: float | None = None
    reasons: list[str] = field(default_factory=list)


def latest_untransferred_percent(snapshots: list[SnapshotView]) -> float | None:
    """Untransferred share of the latest snapshot, percent (None when unknown)."""
    latest = dynamics.latest_snapshot(snapshots)
    return latest.untransferred_percent if latest else None


def matches(
    client: ClientView,
    snapshots: list[SnapshotView],
    criteria: ClientCriteria,
    today: date,
) -> bool:
    """Whether the client satisfies every active criterion."""
    if criteria.client_types and (client.client_type is None or client.client_type.value not in criteria.client_types):
        return False
    if (
        criteria.exclude_client_types
        and client.client_type is not None
        and client.client_type.value in criteria.exclude_client_types
    ):
        return False
    percent = latest_untransferred_percent(snapshots)
    if criteria.min_untransferred_percent is not None and (
        percent is None or percent < criteria.min_untransferred_percent
    ):
        return False
    if criteria.max_untransferred_percent is not None and (
        percent is None or percent > criteria.max_untransferred_percent
    ):
        return False
    if criteria.dynamics_categories:
        category = dynamics.current_category(snapshots, client.created_at.date() if client.created_at else None)
        if category is None or category.value not in criteria.dynamics_categories:
            return False
    if criteria.in_work is not None and client.in_work != criteria.in_work:
        return False
    if criteria.assigned is not None and (client.assignee_id is not None) != criteria.assigned:
        return False
    if criteria.next_contact_overdue is True and not (
        client.next_contact_at is not None and client.next_contact_at <= today
    ):
        return False
    if criteria.has_debt is not None:
        latest = dynamics.latest_snapshot(snapshots)
        has = latest is not None and latest.not_transferred_documents > 0
        if has != criteria.has_debt:
            return False
    return True


def score_client(
    client: ClientView,
    snapshots: list[SnapshotView],
    today: date,
    weights: ScoringWeights,
) -> ScoredClient:
    """Deterministic priority score: 0..sum(weights) with factor explanations."""
    reasons: list[str] = []
    latest = dynamics.latest_snapshot(snapshots)
    percent = latest.untransferred_percent if latest else None
    untransferred = latest.not_transferred_documents if latest else 0

    points = 0.0
    if percent is not None and untransferred > 0:
        share = min(percent, 100.0) / 100.0
        points += weights.untransferred * share
        reasons.append(f"непередано {untransferred} док. ({percent:g}%)")

    category = dynamics.current_category(snapshots, client.created_at.date() if client.created_at else None)
    if category is not None:
        factor = DYNAMICS_CATEGORY_WEIGHT.get(category, 0.0)
        points += weights.dynamics * factor
        if factor > 0:
            reasons.append(f"динамика: {category.value}")

    if client.next_contact_at is not None:
        overdue_days = (today - client.next_contact_at).days
        if overdue_days > 0:
            points += weights.urgency * min(overdue_days, URGENCY_CAP_DAYS) / URGENCY_CAP_DAYS
            reasons.append(f"контакт просрочен на {overdue_days} дн.")
        elif overdue_days == 0:
            points += weights.urgency * 0.8
            reasons.append("контакт назначен на сегодня")

    stage_factor = STAGE_WEIGHT.get(client.stage, 0.0)
    points += weights.stage * stage_factor
    if stage_factor > 0:
        reasons.append(f"этап: {client.stage.value}")

    if client.is_vip:
        points += weights.vip
        reasons.append("VIP-клиент")

    return ScoredClient(
        client=client,
        score=round(points),
        untransferred_percent=percent,
        reasons=reasons,
    )


def rank(
    entries: list[tuple[ClientView, list[SnapshotView]]],
    criteria: ClientCriteria,
    today: date,
    weights: ScoringWeights,
    limit: int,
) -> list[ScoredClient]:
    """Filter by criteria, score, sort and cap — the deterministic core of UC-3."""
    scored = [
        score_client(client, snapshots, today, weights)
        for client, snapshots in entries
        if matches(client, snapshots, criteria, today)
    ]
    # stable order: kcr code ascending, then score/percent by requested direction
    scored.sort(key=lambda item: item.client.kcr_code)
    scored.sort(
        key=lambda item: (item.score, item.untransferred_percent or 0.0), reverse=criteria.sort_direction != "asc"
    )
    return scored[:limit]

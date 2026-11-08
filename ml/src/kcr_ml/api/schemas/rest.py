"""REST request/response schemas (ARCHITECTURE.md §7)."""

from __future__ import annotations

from datetime import date, datetime

from pydantic import Field

from kcr_ml.api.schemas.common import ApiModel, JsonValue
from kcr_ml.application.export_service import ExportKind

# --------------------------------------------------------------------------------------
# UC-2: brief
# --------------------------------------------------------------------------------------


class ClientCardOut(ApiModel):
    id: int
    kcr_code: str
    organization_name: str
    inn: str
    client_type: str | None = None
    stage: str
    assignee_username: str | None = None
    next_contact_at: date | None = None
    created_at: datetime | None = None
    contact_person: str | None = None
    contact_phone: str | None = None
    contact_email: str | None = None


class SnapshotOut(ApiModel):
    reporting_period: str
    snapshot_date: date
    total_documents: int | None = None
    not_transferred_documents: int
    percent_not_transferred: float | None = None


class EventOut(ApiModel):
    id: int
    type: str
    author_username: str
    created_at: datetime
    call_result: str | None = None
    agreement: str | None = None
    client_deadline: date | None = None
    next_contact_at: date | None = None
    comment: str | None = None


class SanctionOut(ApiModel):
    name: str
    status: str


class ClaimOut(ApiModel):
    number: str
    claim_date: date
    status: str
    sanctions: list[SanctionOut] = Field(default_factory=list)


class DynamicsOut(ApiModel):
    category: str
    description: str


class PeriodCategoryOut(ApiModel):
    period: str
    category: str


class BriefOut(ApiModel):
    client: ClientCardOut
    documents: SnapshotOut | None = None
    dynamics: DynamicsOut | None = None
    dynamics_history: list[PeriodCategoryOut] = Field(default_factory=list)
    recent_events: list[EventOut] = Field(default_factory=list)
    claims: list[ClaimOut] = Field(default_factory=list)
    next_action_date: date | None = None
    summary: str


# --------------------------------------------------------------------------------------
# UC-3: prioritize
# --------------------------------------------------------------------------------------


class PrioritizeRequest(ApiModel):
    prompt: str = Field(min_length=1, max_length=2000)
    limit: int = Field(default=20, ge=1, le=100)


class CriteriaOut(ApiModel):
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


class PrioritizedItemOut(ApiModel):
    kcr_code: str
    organization_name: str
    score: int
    untransferred_percent: float | None = None
    reasons: list[str] = Field(default_factory=list)


class PrioritizeOut(ApiModel):
    criteria: CriteriaOut
    items: list[PrioritizedItemOut] = Field(default_factory=list)
    explanation: str


# --------------------------------------------------------------------------------------
# UC-4: analytics
# --------------------------------------------------------------------------------------


class SummaryTotalsOut(ApiModel):
    clients: int
    with_debt: int
    not_transferred_documents: int


class AnalyticsSummaryOut(ApiModel):
    generated_at: datetime
    totals: SummaryTotalsOut
    by_dynamics_category: dict[str, int]
    by_stage: dict[str, int]


class AnalyticsDynamicsOut(ApiModel):
    # Series keys are the canonical lower-cased DynamicsCategory names per §7.
    series: list[dict[str, JsonValue]]
    generated_at: datetime


class TopDebtItemOut(ApiModel):
    kcr_code: str
    organization_name: str
    assignee_username: str | None = None
    reporting_period: str
    snapshot_date: date
    not_transferred_documents: int
    total_documents: int | None = None
    percent_not_transferred: float


class AnalyticsTopDebtOut(ApiModel):
    items: list[TopDebtItemOut] = Field(default_factory=list)
    generated_at: datetime


class OperatorLoadOut(ApiModel):
    username: str
    in_work: int
    due_today: int


class OperatorsTotalsOut(ApiModel):
    assigned: int
    unassigned_in_pool: int
    due_today: int
    due_today_unassigned: int


class AnalyticsOperatorsOut(ApiModel):
    operators: list[OperatorLoadOut] = Field(default_factory=list)
    totals: OperatorsTotalsOut
    generated_at: datetime


# --------------------------------------------------------------------------------------
# UC-5: export
# --------------------------------------------------------------------------------------


class TablePayload(ApiModel):
    title: str = Field(min_length=1, max_length=200)
    columns: list[str] = Field(min_length=1, max_length=50)
    rows: list[list[JsonValue]] = Field(default=[], max_length=10_000)

    def row_width_error(self) -> str | None:
        return None if all(len(row) == len(self.columns) for row in self.rows) else "row width mismatch"


class ExportRequest(ApiModel):
    kind: ExportKind
    title: str | None = Field(default=None, max_length=200)
    table: TablePayload | None = None

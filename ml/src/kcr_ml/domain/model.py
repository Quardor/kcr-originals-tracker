"""Read-model of the kcr-backend data (ARCHITECTURE.md §3, §10).

Pure Python dataclasses and enums mirroring the read-only API contract of
kcr-backend (its ARCHITECTURE.md §11): clients, document transfer snapshots,
client events, claims and sanctions. Enum values match backend enums verbatim.
No framework, HTTP, LLM SDK or pandas imports are allowed here.
"""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from datetime import date, datetime
from enum import StrEnum
from typing import Self


class ClientType(StrEnum):
    """clients.client_type — VIP is expressed through this enum (no separate flag)."""

    VIP = "VIP"
    TECH_PARTNER = "TECH_PARTNER"
    WORKS_VIA_MP = "WORKS_VIA_MP"
    NEWCOMER = "NEWCOMER"


class WorkStage(StrEnum):
    """Work stage state machine of the operator cycle."""

    NOT_IN_WORK = "NOT_IN_WORK"
    IN_POOL = "IN_POOL"
    FIRST_CALL = "FIRST_CALL"
    WAITING_CLIENT_DEADLINE = "WAITING_CLIENT_DEADLINE"
    RECHECK = "RECHECK"
    SECOND_CALL = "SECOND_CALL"
    CLAIM_WORK = "CLAIM_WORK"
    COMPLETED = "COMPLETED"


class ClientEventType(StrEnum):
    """Types of client history events."""

    CALL = "CALL"
    LETTER_SENT = "LETTER_SENT"
    COMMENT = "COMMENT"
    STAGE_CHANGED = "STAGE_CHANGED"
    ASSIGNEE_CHANGED = "ASSIGNEE_CHANGED"
    IMPORT = "IMPORT"
    CLAIM = "CLAIM"
    SANCTION = "SANCTION"


class CallResult(StrEnum):
    """Result of a CALL event."""

    CONNECTED = "CONNECTED"
    NO_ANSWER = "NO_ANSWER"
    CALLBACK_LATER = "CALLBACK_LATER"
    WRONG_NUMBER = "WRONG_NUMBER"


class ClaimStatus(StrEnum):
    NEW = "NEW"
    SENT = "SENT"
    RECEIVED = "RECEIVED"
    SATISFIED = "SATISFIED"
    CANCELLED = "CANCELLED"


class SanctionStatus(StrEnum):
    PLANNED = "PLANNED"
    APPLIED = "APPLIED"
    CANCELLED = "CANCELLED"


class DynamicsCategory(StrEnum):
    """Snapshot dynamics categories — canonical 7 values (backend ARCHITECTURE.md §11.2).

    kcr-backend will compute them at import (migration V11); until then kcr-ml
    computes them deterministically from snapshot history (domain/dynamics.py).
    """

    NO_DEBT = "NO_DEBT"
    FIRST_OCCURRENCE = "FIRST_OCCURRENCE"
    DECREASING = "DECREASING"
    UNCHANGED_OR_GROWING = "UNCHANGED_OR_GROWING"
    REAPPEARED = "REAPPEARED"
    PERSISTENT = "PERSISTENT"
    NEWCOMER_RISK = "NEWCOMER_RISK"


@dataclass(frozen=True, slots=True)
class ClientView:
    """Client directory card (backend ClientResponse)."""

    id: int
    kcr_code: str
    inn: str
    organization_name: str
    kpp: str | None = None
    internal_id: str | None = None
    client_type: ClientType | None = None
    kcr_active: bool | None = None
    kcr_blocked: bool | None = None
    tariff: str | None = None
    ekb_travel_time: str | None = None
    manager: str | None = None
    flags: tuple[str, ...] = ()
    contact_person: str | None = None
    contact_phone: str | None = None
    contact_email: str | None = None
    stage: WorkStage = WorkStage.NOT_IN_WORK
    assignee_id: int | None = None
    assignee_username: str | None = None
    next_contact_at: date | None = None
    created_at: datetime | None = None

    @property
    def is_vip(self) -> bool:
        return self.client_type == ClientType.VIP

    @property
    def in_work(self) -> bool:
        """A client is "in work" once an operator picked it out of the pool."""
        return self.stage not in (WorkStage.NOT_IN_WORK, WorkStage.IN_POOL)


@dataclass(frozen=True, slots=True)
class SnapshotView:
    """One document transfer snapshot (backend SnapshotResponse)."""

    id: int
    client_id: int
    reporting_period: str
    snapshot_date: date
    total_documents: int | None
    not_transferred_documents: int
    percent_not_transferred: float | None = None

    @property
    def untransferred_percent(self) -> float | None:
        """Share of untransferred documents, percent rounded to 2 decimals."""
        if self.percent_not_transferred is not None:
            return self.percent_not_transferred
        if not self.total_documents:
            return None
        return round(self.not_transferred_documents * 100.0 / self.total_documents, 2)


@dataclass(frozen=True, slots=True)
class EventView:
    """One client history event (backend EventResponse)."""

    id: int
    client_id: int
    type: ClientEventType
    author_id: int | None
    author_username: str
    created_at: datetime
    comment: str | None = None
    call_result: CallResult | None = None
    agreement: str | None = None
    client_deadline: date | None = None
    next_contact_at: date | None = None
    record_url: str | None = None


@dataclass(frozen=True, slots=True)
class SanctionView:
    """Sanction attached to a claim."""

    id: int
    claim_id: int
    name: str
    status: SanctionStatus
    created_at: datetime | None = None


@dataclass(frozen=True, slots=True)
class ClaimView:
    """Claim with nested sanctions (backend ClaimResponse)."""

    id: int
    client_id: int
    number: str
    claim_date: date
    incident_number: str | None = None
    received_by_client_at: date | None = None
    status: ClaimStatus = ClaimStatus.NEW
    comment: str | None = None
    created_at: datetime | None = None
    sanctions: tuple[SanctionView, ...] = ()


@dataclass(frozen=True, slots=True)
class Column:
    """One column of a tabular answer shown to the user."""

    key: str
    label: str


JsonValue = str | int | float | bool | None


@dataclass(frozen=True, slots=True)
class TabularData:
    """Table for the client UI / Excel export — numbers and strings from the data source only."""

    title: str
    columns: tuple[Column, ...]
    rows: tuple[tuple[JsonValue, ...], ...]

    def __post_init__(self) -> None:
        width = len(self.columns)
        if any(len(row) != width for row in self.rows):
            raise ValueError(f"TabularData '{self.title}': row width mismatch")

    def limited(self, max_rows: int) -> TabularData:
        """Copy with rows capped; the title notes truncation."""
        if len(self.rows) <= max_rows:
            return self
        return TabularData(
            title=f"{self.title} (первые {max_rows} из {len(self.rows)})",
            columns=self.columns,
            rows=self.rows[:max_rows],
        )

    def as_dicts(self) -> list[dict[str, JsonValue]]:
        """Rows keyed by column key — handy for LLM tool-result payloads."""
        return [dict(zip((c.key for c in self.columns), row, strict=True)) for row in self.rows]


@dataclass(frozen=True, slots=True)
class Page:
    """Page of a paginated backend response (backend PageResponse)."""

    content: tuple[object, ...]
    page: int
    size: int
    total_elements: int
    total_pages: int


@dataclass(frozen=True, slots=True)
class ClientQuery:
    """Filters of the backend client directory (GET /api/v1/clients)."""

    search: str | None = None
    client_type: ClientType | None = None
    stage: WorkStage | None = None
    assignee_id: int | None = None
    unassigned: bool | None = None
    page: int = 0
    size: int = 20
    sort_by: str = "id"
    sort_dir: str = "asc"

    ALLOWED_SORT_FIELDS = frozenset({"id", "kcrCode", "inn", "organizationName", "stage", "nextContactAt"})

    def __post_init__(self) -> None:
        if self.sort_by not in self.ALLOWED_SORT_FIELDS:
            raise ValueError(f"Unsupported sort field: {self.sort_by}")
        if self.sort_dir not in ("asc", "desc"):
            raise ValueError(f"Unsupported sort direction: {self.sort_dir}")
        if self.page < 0 or not 1 <= self.size <= 100:
            raise ValueError("page must be >= 0 and size within 1..100")

    def next_page(self: Self) -> Self:
        return replace(self, page=self.page + 1)


@dataclass(frozen=True, slots=True)
class Principal:
    """Authenticated user decoded from a kcr-backend access token."""

    user_id: int
    username: str
    role: str
    token: str = field(repr=False)

    @property
    def is_admin(self) -> bool:
        return self.role == "ADMIN"

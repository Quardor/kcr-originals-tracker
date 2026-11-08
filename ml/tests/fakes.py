"""Test doubles: in-memory DataSourcePort, scripted LlmPort, seed universe, JWT helper."""

from __future__ import annotations

from collections.abc import Sequence
from datetime import UTC, date, datetime, timedelta
from typing import Any

import jwt as pyjwt

from kcr_ml.application.ports import LlmEvent, LlmMessage, StreamDone, TextDelta, ToolSpec
from kcr_ml.domain.model import (
    CallResult,
    ClaimStatus,
    ClaimView,
    ClientEventType,
    ClientQuery,
    ClientType,
    ClientView,
    EventView,
    Page,
    SanctionStatus,
    SanctionView,
    SnapshotView,
    WorkStage,
)

TEST_SECRET = "test-jwt-secret-kcr-ml-0123456789abcdef"

TODAY = date(2026, 11, 8)
LONG_AGO = datetime(2025, 6, 1, tzinfo=UTC)
P1, P2, P3 = "1 кв. 2026", "2 кв. 2026", "3 кв. 2026"
D1, D2, D3 = date(2026, 4, 15), date(2026, 7, 15), date(2026, 10, 15)
D3B = D3 + timedelta(days=7)


def make_token(
    *,
    sub: str = "1",
    username: str = "admin",
    role: str = "ADMIN",
    secret: str = TEST_SECRET,
) -> str:
    """Sign an access token the same way kcr-backend does (HS256, roles as string)."""
    now = datetime.now(UTC)
    payload = {
        "sub": sub,
        "jti": "test-jti",
        "preferred_username": username,
        "email": None,
        "roles": role,
        "iat": int(now.timestamp()),
        "exp": int((now + timedelta(minutes=15)).timestamp()),
    }
    return pyjwt.encode(payload, secret, algorithm="HS256")


class ScriptedLlm:
    """LlmPort replaying scripted event sequences, one script per chat_stream call."""

    def __init__(self, scripts: Sequence[Sequence[LlmEvent]] | None = None) -> None:
        self.scripts: list[Sequence[LlmEvent]] = list(scripts or [])
        self.calls: list[dict[str, Any]] = []

    async def aclose(self) -> None:
        return None

    async def chat_stream(
        self,
        messages: Sequence[LlmMessage],
        tools: Sequence[ToolSpec] = (),
    ) -> Any:
        self.calls.append({"messages": [dict(m) for m in messages], "tools": [t.name for t in tools]})
        if not self.scripts:
            raise AssertionError("ScriptedLlm has no script left")
        script = self.scripts.pop(0)
        for event in script:
            yield event


def text_script(text: str, *, finish: str = "stop", tokens: int = 7) -> list[LlmEvent]:
    """A script that streams one plain-text answer."""
    return [TextDelta(text), StreamDone(finish_reason=finish, total_tokens=tokens)]


# --------------------------------------------------------------------------------------
# Seed universe
# --------------------------------------------------------------------------------------

OP1_ID, OP1 = 11, "op.morozova"
OP2_ID, OP2 = 12, "op.volkov"


def _client(n: int, kcr: str, name: str, client_type: ClientType | None = None, **kw: Any) -> ClientView:
    defaults: dict[str, Any] = {
        "inn": f"77000000{n:02d}",
        "organization_name": name,
        "client_type": client_type,
        "stage": WorkStage.NOT_IN_WORK,
        "created_at": LONG_AGO,
    }
    defaults.update(kw)
    return ClientView(id=n, kcr_code=kcr, **defaults)


Universe = tuple[list[ClientView], list[SnapshotView], list[EventView], list[ClaimView]]


def build_universe(today: date = TODAY) -> Universe:
    """Deterministic dataset covering every dynamics category and work stage.

    Categories of the latest snapshots:
      1 Альфа PERSISTENT(45%), 2 Бета REAPPEARED(20%), 3 Гамма NEWCOMER_RISK(2.5%),
      4 Дельта DECREASING(10%), 5 Эпсилон NO_DEBT, 6 Вектор FIRST_OCCURRENCE(30%),
      7 Заря FIRST_OCCURRENCE(10%), 8 Иртыш FIRST_OCCURRENCE(5%), 9 Кедр no data,
      10 Ладога UNCHANGED_OR_GROWING(25%).
    """
    clients = [
        _client(1, "kcr_0001", "Альфа-Сервис", ClientType.WORKS_VIA_MP),
        _client(
            2,
            "kcr_0002",
            "Бета-Групп",
            ClientType.VIP,
            stage=WorkStage.FIRST_CALL,
            assignee_id=OP1_ID,
            assignee_username=OP1,
            next_contact_at=today - timedelta(days=2),
        ),
        _client(
            3,
            "kcr_0003",
            "Гамма-Трейд",
            ClientType.NEWCOMER,
            stage=WorkStage.IN_POOL,
            created_at=datetime.combine(today - timedelta(days=60), datetime.min.time(), tzinfo=UTC),
        ),
        _client(4, "kcr_0004", "Дельта-Плюс", ClientType.WORKS_VIA_MP, stage=WorkStage.IN_POOL),
        _client(
            5,
            "kcr_0005",
            "Эпсилон",
            None,
            stage=WorkStage.COMPLETED,
            assignee_id=OP2_ID,
            assignee_username=OP2,
            next_contact_at=today + timedelta(days=10),
        ),
        _client(6, "kcr_0006", "Вектор", ClientType.WORKS_VIA_MP),
        _client(
            7,
            "kcr_0007",
            "Заря",
            ClientType.TECH_PARTNER,
            stage=WorkStage.WAITING_CLIENT_DEADLINE,
            assignee_id=OP2_ID,
            assignee_username=OP2,
            next_contact_at=today + timedelta(days=3),
        ),
        _client(
            8,
            "kcr_0008",
            "Иртыш",
            ClientType.VIP,
            stage=WorkStage.SECOND_CALL,
            assignee_id=OP1_ID,
            assignee_username=OP1,
            next_contact_at=today,
        ),
        _client(9, "kcr_0009", "Кедр", None),
        _client(10, "kcr_0010", "Ладога", ClientType.WORKS_VIA_MP),
    ]

    def snap(sid: int, cid: int, period: str, day: date, total: int, nt: int) -> SnapshotView:
        return SnapshotView(
            id=sid,
            client_id=cid,
            reporting_period=period,
            snapshot_date=day,
            total_documents=total,
            not_transferred_documents=nt,
        )

    snapshots = [
        snap(1, 1, P1, D1, 100, 40),
        snap(2, 1, P2, D2, 100, 45),
        snap(3, 1, P3, D3, 100, 45),
        snap(4, 2, P1, D1, 50, 5),
        snap(5, 2, P2, D2, 50, 0),
        snap(6, 2, P3, D3, 50, 10),
        snap(7, 3, P3, D3, 80, 2),
        snap(8, 4, P1, D1, 100, 30),
        snap(9, 4, P2, D2, 100, 20),
        snap(10, 4, P3, D3, 100, 10),
        snap(11, 5, P1, D1, 100, 0),
        snap(12, 5, P2, D2, 100, 0),
        snap(13, 6, P1, D1, 60, 0),
        snap(14, 6, P2, D2, 60, 0),
        snap(15, 6, P3, D3, 60, 18),
        snap(16, 7, P1, D1, 90, 0),
        snap(17, 7, P2, D2, 90, 0),
        snap(18, 7, P3, D3, 90, 9),
        snap(19, 8, P1, D1, 40, 0),
        snap(20, 8, P2, D2, 40, 0),
        snap(21, 8, P3, D3, 40, 2),
        snap(22, 10, P1, D1, 100, 0),
        snap(23, 10, P2, D2, 100, 0),
        snap(24, 10, P3, D3, 100, 25),
        snap(25, 10, P3, D3B, 100, 25),
    ]

    events = [
        EventView(
            id=1,
            client_id=2,
            type=ClientEventType.CALL,
            author_id=OP1_ID,
            author_username=OP1,
            created_at=datetime(2026, 11, 5, 12, 0, tzinfo=UTC),
            call_result=CallResult.CONNECTED,
            agreement="Передаст оригиналы до 20 ноября",
            client_deadline=date(2026, 11, 20),
            next_contact_at=today - timedelta(days=2),
        ),
        EventView(
            id=2,
            client_id=2,
            type=ClientEventType.COMMENT,
            author_id=OP1_ID,
            author_username=OP1,
            created_at=datetime(2026, 11, 4, 10, 0, tzinfo=UTC),
            comment="Отправили повторное письмо",
        ),
        EventView(
            id=3,
            client_id=2,
            type=ClientEventType.LETTER_SENT,
            author_id=OP1_ID,
            author_username=OP1,
            created_at=datetime(2026, 11, 1, 9, 0, tzinfo=UTC),
        ),
        EventView(
            id=4,
            client_id=6,
            type=ClientEventType.CALL,
            author_id=1,
            author_username="admin",
            created_at=datetime(2026, 10, 20, 15, 0, tzinfo=UTC),
            call_result=CallResult.NO_ANSWER,
        ),
    ]

    claims = [
        ClaimView(
            id=1,
            client_id=2,
            number="П-101",
            claim_date=date(2026, 10, 1),
            status=ClaimStatus.SENT,
            created_at=datetime(2026, 10, 1, tzinfo=UTC),
            sanctions=(
                SanctionView(
                    id=1,
                    claim_id=1,
                    name="Ограничение доступа",
                    status=SanctionStatus.APPLIED,
                    created_at=datetime(2026, 10, 5, tzinfo=UTC),
                ),
            ),
        ),
    ]
    return clients, snapshots, events, claims


class FakeDataSource:
    """In-memory read-only DataSourcePort with backend-like filtering and paging."""

    def __init__(
        self,
        clients: list[ClientView] | None = None,
        snapshots: list[SnapshotView] | None = None,
        events: list[EventView] | None = None,
        claims: list[ClaimView] | None = None,
        *,
        today: date = TODAY,
    ) -> None:
        if clients is None:
            clients, snapshots, events, claims = build_universe(today)
        self.clients = list(clients)
        self.snapshots = list(snapshots or [])
        self.events = list(events or [])
        self.claims = list(claims or [])
        self.today = today
        self.calls: list[str] = []

    # -- helpers ---------------------------------------------------------------------

    def snapshots_of(self, client_id: int) -> list[SnapshotView]:
        return sorted((s for s in self.snapshots if s.client_id == client_id), key=lambda s: (s.snapshot_date, s.id))

    # -- DataSourcePort --------------------------------------------------------------

    async def list_clients(self, query: ClientQuery) -> Page:
        self.calls.append("list_clients")
        rows = list(self.clients)
        if query.search:
            needle = query.search.lower()
            rows = [c for c in rows if needle in c.kcr_code.lower() or needle in c.inn.lower()]
        if query.client_type is not None:
            rows = [c for c in rows if c.client_type == query.client_type]
        if query.stage is not None:
            rows = [c for c in rows if c.stage == query.stage]
        if query.assignee_id is not None:
            rows = [c for c in rows if c.assignee_id == query.assignee_id]
        if query.unassigned:
            rows = [c for c in rows if c.assignee_id is None]
        sort_key = {
            "id": lambda c: c.id,
            "kcrCode": lambda c: c.kcr_code,
            "inn": lambda c: c.inn,
            "organizationName": lambda c: c.organization_name,
            "stage": lambda c: c.stage.value,
            "nextContactAt": lambda c: (c.next_contact_at is None, c.next_contact_at or date.min),
        }[query.sort_by]
        rows.sort(key=sort_key, reverse=query.sort_dir == "desc")
        total = len(rows)
        start = query.page * query.size
        content = tuple(rows[start : start + query.size])
        return Page(
            content=content,
            page=query.page,
            size=query.size,
            total_elements=total,
            total_pages=(total + query.size - 1) // query.size,
        )

    async def get_client(self, client_id: int) -> ClientView | None:
        self.calls.append("get_client")
        return next((c for c in self.clients if c.id == client_id), None)

    async def list_snapshots(self, client_id: int, reporting_period: str | None = None) -> list[SnapshotView]:
        self.calls.append("list_snapshots")
        rows = self.snapshots_of(client_id)
        if reporting_period is not None:
            rows = [s for s in rows if s.reporting_period == reporting_period]
        return rows

    async def list_events(self, client_id: int, page: int = 0, size: int = 20) -> Page:
        self.calls.append("list_events")
        rows = sorted(
            (e for e in self.events if e.client_id == client_id), key=lambda e: (e.created_at, e.id), reverse=True
        )
        total = len(rows)
        start = page * size
        return Page(
            content=tuple(rows[start : start + size]),
            page=page,
            size=size,
            total_elements=total,
            total_pages=(total + size - 1) // size,
        )

    async def list_claims(self, client_id: int) -> list[ClaimView]:
        self.calls.append("list_claims")
        return [c for c in self.claims if c.client_id == client_id]

    async def today_worklist(self, page: int = 0, size: int = 20) -> Page:
        self.calls.append("today_worklist")
        rows = sorted(
            (c for c in self.clients if c.next_contact_at is not None and c.next_contact_at <= self.today),
            key=lambda c: (c.next_contact_at, c.id),  # type: ignore[type-var]
        )
        total = len(rows)
        start = page * size
        return Page(
            content=tuple(rows[start : start + size]),
            page=page,
            size=size,
            total_elements=total,
            total_pages=(total + size - 1) // size,
        )

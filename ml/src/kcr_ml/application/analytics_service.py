"""UC-4: dashboard aggregates — series, distributions and tops (ARCHITECTURE.md §2, §7).

Aggregation happens here, on top of the read-only API of kcr-backend; the LLM is
not involved at all. Numbers come exclusively from the data source.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, date, datetime

from kcr_ml.application.ports import DataSourcePort, iter_all_clients
from kcr_ml.domain import dynamics
from kcr_ml.domain.model import ClientView, DynamicsCategory, SnapshotView, WorkStage

# Series keys per ARCHITECTURE.md §7 — canonical enum names lower-cased.
DYNAMICS_SERIES_KEYS: tuple[str, ...] = tuple(c.value.lower() for c in DynamicsCategory)


@dataclass(frozen=True, slots=True)
class ClientWithSnapshots:
    """Client with its full snapshot history — the unit of all aggregates."""

    client: ClientView
    snapshots: list[SnapshotView]


async def load_clients_with_snapshots(
    source: DataSourcePort,
    max_clients: int,
) -> list[ClientWithSnapshots]:
    """Load the client directory plus snapshot histories (bounded by ``max_clients``)."""
    entries: list[ClientWithSnapshots] = []
    for client in await iter_all_clients(source, max_clients=max_clients):
        snapshots = await source.list_snapshots(client.id)
        entries.append(ClientWithSnapshots(client=client, snapshots=snapshots))
    return entries


def _created_on(client: ClientView) -> date | None:
    return client.created_at.date() if client.created_at else None


def _now() -> datetime:
    return datetime.now(UTC)


async def summary(source: DataSourcePort, max_clients: int) -> dict[str, object]:
    """Counters: dynamics categories, work stages, debt totals."""
    entries = await load_clients_with_snapshots(source, max_clients)
    by_category = {c.value: 0 for c in DynamicsCategory}
    by_stage = {s.value: 0 for s in WorkStage}
    with_debt = 0
    not_transferred_total = 0
    for entry in entries:
        category = dynamics.current_category(entry.snapshots, _created_on(entry.client))
        if category is not None:
            by_category[category.value] += 1
        by_stage[entry.client.stage.value] += 1
        latest = dynamics.latest_snapshot(entry.snapshots)
        if latest is not None and latest.not_transferred_documents > 0:
            with_debt += 1
            not_transferred_total += latest.not_transferred_documents
    return {
        "generated_at": _now(),
        "totals": {
            "clients": len(entries),
            "with_debt": with_debt,
            "not_transferred_documents": not_transferred_total,
        },
        "by_dynamics_category": by_category,
        "by_stage": by_stage,
    }


async def dynamics_series(source: DataSourcePort, max_clients: int) -> dict[str, object]:
    """Per-reporting-period counts of each dynamics category (chart-ready)."""
    entries = await load_clients_with_snapshots(source, max_clients)
    counts: dict[str, dict[str, int]] = {}
    period_last_date: dict[str, date] = {}
    for entry in entries:
        for snapshot in entry.snapshots:
            current = period_last_date.get(snapshot.reporting_period)
            if current is None or snapshot.snapshot_date > current:
                period_last_date[snapshot.reporting_period] = snapshot.snapshot_date
        for period in dynamics.classify_history(entry.snapshots, _created_on(entry.client)):
            bucket = counts.setdefault(period.period, dict.fromkeys(DYNAMICS_SERIES_KEYS, 0))
            bucket[period.category.value.lower()] += 1
    ordered_periods = sorted(counts, key=lambda p: (period_last_date.get(p, date.min), p))
    series = [{"period": period, **counts[period]} for period in ordered_periods]
    return {"series": series, "generated_at": _now()}


async def top_debt(
    source: DataSourcePort,
    limit: int,
    max_clients: int,
) -> dict[str, object]:
    """Clients with the highest untransferred share (latest snapshot)."""
    entries = await load_clients_with_snapshots(source, max_clients)
    rows: list[tuple[float, int, ClientView, SnapshotView]] = []
    for entry in entries:
        latest = dynamics.latest_snapshot(entry.snapshots)
        if latest is None or latest.not_transferred_documents <= 0:
            continue
        percent = latest.untransferred_percent
        if percent is None:
            continue
        rows.append((percent, latest.not_transferred_documents, entry.client, latest))
    # stable order: kcr code ascending, then percent/count descending
    rows.sort(key=lambda row: row[2].kcr_code)
    rows.sort(key=lambda row: (row[0], row[1]), reverse=True)
    items = [
        {
            "kcr_code": client.kcr_code,
            "organization_name": client.organization_name,
            "assignee_username": client.assignee_username,
            "reporting_period": latest.reporting_period,
            "snapshot_date": latest.snapshot_date.isoformat(),
            "not_transferred_documents": latest.not_transferred_documents,
            "total_documents": latest.total_documents,
            "percent_not_transferred": percent,
        }
        for percent, _count, client, latest in rows[:limit]
    ]
    return {"items": items, "generated_at": _now()}


async def operators(source: DataSourcePort, today: date, max_clients: int) -> dict[str, object]:
    """Operator load: clients in work and due for contact today, plus the unassigned pool."""
    clients = await iter_all_clients(source, max_clients=max_clients)
    per_operator: dict[str, dict[str, int]] = {}
    unassigned_in_pool = 0
    unassigned_due_today = 0
    due_today_total = 0
    for client in clients:
        due = client.next_contact_at is not None and client.next_contact_at <= today
        if due:
            due_today_total += 1
        if client.assignee_username is None:
            if client.stage == WorkStage.IN_POOL:
                unassigned_in_pool += 1
            if due:
                unassigned_due_today += 1
            continue
        bucket = per_operator.setdefault(client.assignee_username, {"in_work": 0, "due_today": 0})
        bucket["in_work"] += 1
        if due:
            bucket["due_today"] += 1
    operators_list = [
        {"username": username, "in_work": bucket["in_work"], "due_today": bucket["due_today"]}
        for username, bucket in sorted(per_operator.items())
    ]
    return {
        "operators": operators_list,
        "totals": {
            "assigned": sum(b["in_work"] for b in per_operator.values()),
            "unassigned_in_pool": unassigned_in_pool,
            "due_today": due_today_total,
            "due_today_unassigned": unassigned_due_today,
        },
        "generated_at": _now(),
    }

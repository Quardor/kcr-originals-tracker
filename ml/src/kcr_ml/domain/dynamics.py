"""Deterministic computation of DynamicsCategory from snapshot history.

Rules mirror kcr-backend ARCHITECTURE.md §11.2 (categories are computed there at
snapshot import, migration V11). Until the backend exposes the column, kcr-ml
computes the same categories client-side — deterministic code, never the LLM.

Decision tree for one snapshot (evaluated against the history prefix ending at
it, snapshots ordered by snapshot_date, id); the §11.2 rules overlap, so the
most informative applicable category wins:

1. ``not_transferred == 0``                        → NO_DEBT
2. client connected < 6 months before the snapshot → NEWCOMER_RISK
3. debt existed earlier and was fully repaid since → REAPPEARED (cycle completed)
4. no debt in any earlier snapshot                 → FIRST_OCCURRENCE
5. debt decreased vs the previous snapshot         → DECREASING (improving beats "long")
6. otherwise: debt also in earlier periods         → PERSISTENT (multiple periods in a row)
7. otherwise                                       → UNCHANGED_OR_GROWING
"""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import date

from kcr_ml.domain.model import DynamicsCategory, SnapshotView

NEWCOMER_MONTHS = 6


def _months_between(start: date, end: date) -> int:
    """Whole months between two dates (day-of-month aware)."""
    months = (end.year - start.year) * 12 + (end.month - start.month)
    if end.day < start.day:
        months -= 1
    return months


def is_newcomer(client_created_at: date | None, on_date: date) -> bool:
    """Client connected less than NEWCOMER_MONTHS before on_date."""
    return client_created_at is not None and _months_between(client_created_at, on_date) < NEWCOMER_MONTHS


@dataclass(frozen=True, slots=True)
class PeriodCategory:
    """Category of one reporting period — that of the period's last snapshot."""

    period: str
    snapshot_date: date
    category: DynamicsCategory


def _ordered_period_prefixes(
    snapshots: Sequence[SnapshotView],
) -> list[tuple[str, list[SnapshotView]]]:
    """Group snapshots by reporting period, periods ordered by their latest snapshot date."""
    groups: dict[str, list[SnapshotView]] = {}
    for snapshot in snapshots:
        groups.setdefault(snapshot.reporting_period, []).append(snapshot)
    return sorted(
        ((period, items) for period, items in groups.items()),
        key=lambda pair: (max(s.snapshot_date for s in pair[1]), pair[0]),
    )


def classify_snapshot(
    prefix: Sequence[SnapshotView],
    client_created_at: date | None,
) -> DynamicsCategory:
    """Category of the last snapshot of ``prefix`` (rules in the module docstring)."""
    if not prefix:
        raise ValueError("prefix must contain at least one snapshot")
    current = prefix[-1]
    if current.not_transferred_documents == 0:
        return DynamicsCategory.NO_DEBT
    if is_newcomer(client_created_at, current.snapshot_date):
        return DynamicsCategory.NEWCOMER_RISK

    earlier = prefix[:-1]
    seen_debt = False
    had_zero_after_debt = False
    debt_in_earlier_period = False
    for snapshot in earlier:
        if snapshot.not_transferred_documents > 0:
            seen_debt = True
            if snapshot.reporting_period != current.reporting_period:
                debt_in_earlier_period = True
        elif seen_debt:
            had_zero_after_debt = True
    if seen_debt and had_zero_after_debt:
        return DynamicsCategory.REAPPEARED
    if not seen_debt:
        return DynamicsCategory.FIRST_OCCURRENCE

    previous = earlier[-1]
    if current.not_transferred_documents < previous.not_transferred_documents:
        return DynamicsCategory.DECREASING
    if debt_in_earlier_period:
        return DynamicsCategory.PERSISTENT
    return DynamicsCategory.UNCHANGED_OR_GROWING


def classify_history(
    snapshots: Iterable[SnapshotView],
    client_created_at: date | None,
) -> list[PeriodCategory]:
    """Category per reporting period: that of each period's last snapshot."""
    ordered = sorted(snapshots, key=lambda s: (s.snapshot_date, s.id))
    result: list[PeriodCategory] = []
    for _period, items in _ordered_period_prefixes(ordered):
        last = items[-1]
        prefix = ordered[: ordered.index(last) + 1]
        result.append(
            PeriodCategory(
                period=last.reporting_period,
                snapshot_date=last.snapshot_date,
                category=classify_snapshot(prefix, client_created_at),
            )
        )
    return result


def current_category(
    snapshots: Iterable[SnapshotView],
    client_created_at: date | None,
) -> DynamicsCategory | None:
    """Category of the client right now — that of the latest snapshot."""
    history = classify_history(snapshots, client_created_at)
    return history[-1].category if history else None


def latest_snapshot(snapshots: Iterable[SnapshotView]) -> SnapshotView | None:
    ordered = sorted(snapshots, key=lambda s: (s.snapshot_date, s.id))
    return ordered[-1] if ordered else None

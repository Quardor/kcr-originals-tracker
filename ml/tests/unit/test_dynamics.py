"""Unit tests of the deterministic dynamics classification (§11.2 rules)."""

from __future__ import annotations

from datetime import date

from kcr_ml.domain.dynamics import classify_history, classify_snapshot, current_category, is_newcomer
from kcr_ml.domain.model import DynamicsCategory, SnapshotView

CREATED = date(2025, 1, 10)
D1, D2, D3, D4 = date(2026, 1, 15), date(2026, 4, 15), date(2026, 7, 15), date(2026, 7, 22)


def snap(n: int, period: str, day: date, nt: int, total: int = 100) -> SnapshotView:
    return SnapshotView(
        id=n,
        client_id=1,
        reporting_period=period,
        snapshot_date=day,
        total_documents=total,
        not_transferred_documents=nt,
    )


def test_no_debt() -> None:
    history = [snap(1, "P1", D1, 0), snap(2, "P2", D2, 0)]
    assert classify_snapshot(history, CREATED) is DynamicsCategory.NO_DEBT


def test_newcomer_risk_even_below_pool_threshold() -> None:
    newcomer_created = date(2026, 6, 1)  # ~1.5 months before the snapshot
    history = [snap(1, "P2", D2, 2, total=80)]  # 2.5% — below 5%, still risky
    assert classify_snapshot(history, newcomer_created) is DynamicsCategory.NEWCOMER_RISK


def test_newcomer_flag_respects_month_boundary() -> None:
    assert is_newcomer(date(2026, 6, 1), date(2026, 11, 30)) is True  # 5 full months
    assert is_newcomer(date(2026, 6, 1), date(2026, 12, 1)) is False  # exactly 6 months
    assert is_newcomer(date(2026, 6, 2), date(2026, 12, 1)) is True  # one day short of 6 months


def test_first_occurrence() -> None:
    history = [snap(1, "P1", D1, 0), snap(2, "P2", D2, 0), snap(3, "P3", D3, 15)]
    assert classify_snapshot(history, CREATED) is DynamicsCategory.FIRST_OCCURRENCE


def test_reappeared_after_full_repayment() -> None:
    history = [snap(1, "P1", D1, 5), snap(2, "P2", D2, 0), snap(3, "P3", D3, 10)]
    assert classify_snapshot(history, CREATED) is DynamicsCategory.REAPPEARED


def test_reappeared_within_period() -> None:
    history = [snap(1, "P1", D1, 5), snap(2, "P2", D2, 0), snap(3, "P2b", D3, 0), snap(4, "P2c", D4, 3)]
    assert classify_snapshot(history, CREATED) is DynamicsCategory.REAPPEARED


def test_decreasing_beats_persistent_when_improving() -> None:
    history = [snap(1, "P1", D1, 30), snap(2, "P2", D2, 20), snap(3, "P3", D3, 10)]
    assert classify_snapshot(history, CREATED) is DynamicsCategory.DECREASING


def test_persistent_when_not_decreasing_across_periods() -> None:
    history = [snap(1, "P1", D1, 40), snap(2, "P2", D2, 45), snap(3, "P3", D3, 45)]
    assert classify_snapshot(history, CREATED) is DynamicsCategory.PERSISTENT


def test_unchanged_or_growing_within_single_period() -> None:
    history = [
        snap(1, "P1", D1, 0),
        snap(2, "P2", D2, 0),
        snap(3, "P3", D3, 25),
        snap(4, "P3", D4, 25),
    ]
    assert classify_snapshot(history, CREATED) is DynamicsCategory.UNCHANGED_OR_GROWING


def test_growing_within_period_is_not_persistent_yet() -> None:
    history = [
        snap(1, "P1", D1, 0),
        snap(2, "P2", D2, 0),
        snap(3, "P3", D3, 10),
        snap(4, "P3", D4, 30),
    ]
    assert classify_snapshot(history, CREATED) is DynamicsCategory.UNCHANGED_OR_GROWING


def test_classify_history_gives_category_per_period() -> None:
    snapshots = [snap(1, "P1", D1, 5), snap(2, "P2", D2, 0), snap(3, "P3", D3, 10), snap(4, "P3", D4, 12)]
    history = classify_history(snapshots, CREATED)
    assert [(p.period, p.category) for p in history] == [
        ("P1", DynamicsCategory.FIRST_OCCURRENCE),
        ("P2", DynamicsCategory.NO_DEBT),
        ("P3", DynamicsCategory.REAPPEARED),
    ]
    assert current_category(snapshots, CREATED) is DynamicsCategory.REAPPEARED


def test_empty_history_has_no_category() -> None:
    assert current_category([], CREATED) is None

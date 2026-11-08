"""Unit tests of analytics aggregation (UC-4) on the seeded universe."""

from __future__ import annotations

import pytest

from kcr_ml.application import analytics_service
from tests.fakes import TODAY, FakeDataSource


@pytest.fixture
def source() -> FakeDataSource:
    return FakeDataSource(today=TODAY)


async def test_summary_counts(source: FakeDataSource) -> None:
    data = await analytics_service.summary(source, max_clients=100)
    assert data["totals"] == {"clients": 10, "with_debt": 8, "not_transferred_documents": 121}
    assert data["by_dynamics_category"] == {  # type: ignore[comparison-overlap]
        "NO_DEBT": 1,
        "FIRST_OCCURRENCE": 3,
        "DECREASING": 1,
        "UNCHANGED_OR_GROWING": 1,
        "REAPPEARED": 1,
        "PERSISTENT": 1,
        "NEWCOMER_RISK": 1,
    }
    assert data["by_stage"] == {  # type: ignore[comparison-overlap]
        "NOT_IN_WORK": 4,
        "IN_POOL": 2,
        "FIRST_CALL": 1,
        "WAITING_CLIENT_DEADLINE": 1,
        "SECOND_CALL": 1,
        "COMPLETED": 1,
        "RECHECK": 0,
        "CLAIM_WORK": 0,
    }


async def test_dynamics_series_per_period(source: FakeDataSource) -> None:
    data = await analytics_service.dynamics_series(source, max_clients=100)
    series = data["series"]
    assert [row["period"] for row in series] == ["1 кв. 2026", "2 кв. 2026", "3 кв. 2026"]
    p1 = series[0]
    assert p1["no_debt"] == 5  # clients 5, 6, 7, 8, 10
    assert p1["first_occurrence"] == 3  # clients 1, 2, 4
    p3 = series[2]
    assert p3["persistent"] == 1  # client 1
    assert p3["reappeared"] == 1  # client 2
    assert p3["newcomer_risk"] == 1  # client 3
    assert p3["decreasing"] == 1  # client 4
    assert p3["unchanged_or_growing"] == 1  # client 10
    assert p3["first_occurrence"] == 3  # clients 6, 7, 8
    assert p3["no_debt"] == 0


async def test_top_debt_orders_by_percent(source: FakeDataSource) -> None:
    data = await analytics_service.top_debt(source, limit=5, max_clients=100)
    codes = [item["kcr_code"] for item in data["items"]]
    assert codes == ["kcr_0001", "kcr_0006", "kcr_0010", "kcr_0002", "kcr_0004"]
    assert data["items"][0]["percent_not_transferred"] == 45  # type: ignore[index]


async def test_operators_load(source: FakeDataSource) -> None:
    data = await analytics_service.operators(source, TODAY, max_clients=100)
    by_user = {item["username"]: item for item in data["operators"]}
    assert by_user["op.morozova"] == {"username": "op.morozova", "in_work": 2, "due_today": 2}
    assert by_user["op.volkov"] == {"username": "op.volkov", "in_work": 2, "due_today": 0}
    assert data["totals"] == {"assigned": 4, "unassigned_in_pool": 2, "due_today": 2, "due_today_unassigned": 0}

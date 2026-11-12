"""Unit tests of criteria filtering and deterministic scoring (UC-3 core)."""

from __future__ import annotations

import pytest

from kcr_ml.domain.criteria import ClientCriteria, ScoringWeights, matches, rank, score_client
from tests.fakes import TODAY, ClientView, FakeDataSource


@pytest.fixture
def universe() -> FakeDataSource:
    return FakeDataSource(today=TODAY)


async def _entries(source: FakeDataSource) -> list[tuple[ClientView, list[object]]]:
    return [(client, await source.list_snapshots(client.id)) for client in source.clients]


async def test_matches_percent_and_type_filters(universe: FakeDataSource) -> None:
    criteria = ClientCriteria(exclude_client_types=["VIP"], min_untransferred_percent=25, in_work=False).normalized()
    matched = {
        client.kcr_code
        for client, snapshots in await _entries(universe)
        if matches(client, snapshots, criteria, TODAY)  # type: ignore[arg-type]
    }
    assert matched == {"kcr_0001", "kcr_0006", "kcr_0010"}


async def test_matches_dynamics_category_filter(universe: FakeDataSource) -> None:
    criteria = ClientCriteria(dynamics_categories=["REAPPEARED"]).normalized()
    matched = {
        client.kcr_code
        for client, snapshots in await _entries(universe)
        if matches(client, snapshots, criteria, TODAY)  # type: ignore[arg-type]
    }
    assert matched == {"kcr_0002"}


async def test_matches_overdue_contact(universe: FakeDataSource) -> None:
    criteria = ClientCriteria(next_contact_overdue=True).normalized()
    matched = {
        client.kcr_code
        for client, snapshots in await _entries(universe)
        if matches(client, snapshots, criteria, TODAY)  # type: ignore[arg-type]
    }
    assert matched == {"kcr_0002", "kcr_0008"}


async def test_score_is_explainable_and_bounded(universe: FakeDataSource) -> None:
    entries = {client.id: snapshots for client, snapshots in await _entries(universe)}
    client1 = next(c for c in universe.clients if c.id == 1)  # 45%, persistent
    scored = score_client(client1, entries[1], TODAY, ScoringWeights())  # type: ignore[arg-type]
    assert scored.score == 18 + 25  # 40 * 0.45 + 25 * 1.0
    assert any("45%" in reason for reason in scored.reasons)
    assert any("PERSISTENT" in reason for reason in scored.reasons)

    client8 = next(c for c in universe.clients if c.id == 8)  # VIP, contact due today, 5%
    vip_scored = score_client(client8, entries[8], TODAY, ScoringWeights())  # type: ignore[arg-type]
    assert vip_scored.score == round(40 * 0.05 + 25 * 0.6 + 20 * 0.8 + 10 * 0.9 + 5)


async def test_rank_orders_desc_and_caps(universe: FakeDataSource) -> None:
    criteria = ClientCriteria(min_untransferred_percent=25).normalized()
    ranked = rank(await _entries(universe), criteria, TODAY, ScoringWeights(), limit=2)  # type: ignore[arg-type]
    assert [item.client.kcr_code for item in ranked] == ["kcr_0001", "kcr_0010"]
    assert all(item.untransferred_percent is not None and item.untransferred_percent >= 25 for item in ranked)


async def test_rank_asc_direction(universe: FakeDataSource) -> None:
    criteria = ClientCriteria(min_untransferred_percent=25, sort_direction="asc").normalized()
    ranked = rank(await _entries(universe), criteria, TODAY, ScoringWeights(), limit=10)  # type: ignore[arg-type]
    scores = [item.score for item in ranked]
    assert scores == sorted(scores)


def test_normalized_rejects_bad_direction() -> None:
    with pytest.raises(ValueError, match="sort_direction"):
        ClientCriteria(sort_direction="random").normalized()

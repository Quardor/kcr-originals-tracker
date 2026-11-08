"""UC-4: dashboard aggregates — REST routes (thin: schema → use-case → schema)."""

from __future__ import annotations

from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends, Query

from kcr_ml.api.deps import get_data_source, get_principal, get_settings, get_today
from kcr_ml.api.schemas.rest import (
    AnalyticsDynamicsOut,
    AnalyticsOperatorsOut,
    AnalyticsSummaryOut,
    AnalyticsTopDebtOut,
)
from kcr_ml.application import analytics_service
from kcr_ml.application.ports import DataSourcePort
from kcr_ml.config import Settings
from kcr_ml.domain.model import Principal

router = APIRouter(prefix="/api/v1/ai/analytics", tags=["analytics"])


@router.get("/summary", response_model=AnalyticsSummaryOut)
async def summary(
    source: Annotated[DataSourcePort, Depends(get_data_source)],
    settings: Annotated[Settings, Depends(get_settings)],
    _principal: Annotated[Principal, Depends(get_principal)],
) -> dict[str, object]:
    return await analytics_service.summary(source, settings.ai_max_scan_clients)


@router.get("/dynamics", response_model=AnalyticsDynamicsOut)
async def dynamics(
    source: Annotated[DataSourcePort, Depends(get_data_source)],
    settings: Annotated[Settings, Depends(get_settings)],
    _principal: Annotated[Principal, Depends(get_principal)],
) -> dict[str, object]:
    return await analytics_service.dynamics_series(source, settings.ai_max_scan_clients)


@router.get("/top-debt", response_model=AnalyticsTopDebtOut)
async def top_debt(
    source: Annotated[DataSourcePort, Depends(get_data_source)],
    settings: Annotated[Settings, Depends(get_settings)],
    _principal: Annotated[Principal, Depends(get_principal)],
    limit: Annotated[int, Query(ge=1, le=100)] = 10,
) -> dict[str, object]:
    return await analytics_service.top_debt(source, limit, settings.ai_max_scan_clients)


@router.get("/operators", response_model=AnalyticsOperatorsOut)
async def operators(
    source: Annotated[DataSourcePort, Depends(get_data_source)],
    settings: Annotated[Settings, Depends(get_settings)],
    today: Annotated[date, Depends(get_today)],
    _principal: Annotated[Principal, Depends(get_principal)],
) -> dict[str, object]:
    return await analytics_service.operators(source, today, settings.ai_max_scan_clients)

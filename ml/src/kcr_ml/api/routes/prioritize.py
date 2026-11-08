"""UC-3: priority selection — REST route (admin-only, §2)."""

from __future__ import annotations

from dataclasses import asdict
from datetime import date
from typing import Annotated

from fastapi import APIRouter, Depends

from kcr_ml.api.deps import (
    get_data_source,
    get_llm,
    get_scoring_weights,
    get_settings,
    get_today,
    require_admin,
)
from kcr_ml.api.schemas.rest import CriteriaOut, PrioritizedItemOut, PrioritizeOut, PrioritizeRequest
from kcr_ml.application.ports import DataSourcePort, LlmPort
from kcr_ml.application.prioritize_service import prioritize
from kcr_ml.config import Settings
from kcr_ml.domain.criteria import ScoringWeights
from kcr_ml.domain.model import Principal

router = APIRouter(prefix="/api/v1/ai", tags=["prioritize"])


@router.post("/prioritize", response_model=PrioritizeOut)
async def prioritize_clients(
    request: PrioritizeRequest,
    source: Annotated[DataSourcePort, Depends(get_data_source)],
    llm: Annotated[LlmPort, Depends(get_llm)],
    settings: Annotated[Settings, Depends(get_settings)],
    weights: Annotated[ScoringWeights, Depends(get_scoring_weights)],
    today: Annotated[date, Depends(get_today)],
    _admin: Annotated[Principal, Depends(require_admin)],
) -> PrioritizeOut:
    result = await prioritize(
        source,
        llm,
        request.prompt,
        limit=request.limit,
        weights=weights,
        today=today,
        max_clients=settings.ai_max_scan_clients,
    )
    return PrioritizeOut(
        criteria=CriteriaOut(**asdict(result.criteria)),
        items=[
            PrioritizedItemOut(
                kcr_code=item.client.kcr_code,
                organization_name=item.client.organization_name,
                score=item.score,
                untransferred_percent=item.untransferred_percent,
                reasons=item.reasons,
            )
            for item in result.items
        ],
        explanation=result.explanation,
    )

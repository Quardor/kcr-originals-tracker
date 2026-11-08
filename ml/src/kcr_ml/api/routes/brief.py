"""UC-2: pre-call brief — REST route."""

from __future__ import annotations

from typing import Annotated

from fastapi import APIRouter, Depends

from kcr_ml.api.deps import get_data_source, get_llm, get_principal
from kcr_ml.api.schemas.rest import (
    BriefOut,
    ClaimOut,
    ClientCardOut,
    DynamicsOut,
    EventOut,
    PeriodCategoryOut,
    SanctionOut,
    SnapshotOut,
)
from kcr_ml.application.brief_service import build_brief
from kcr_ml.application.ports import DataSourcePort, LlmPort
from kcr_ml.domain.model import Principal

router = APIRouter(prefix="/api/v1/ai", tags=["brief"])


@router.get("/clients/{client_id}/brief", response_model=BriefOut)
async def client_brief(
    client_id: int,
    source: Annotated[DataSourcePort, Depends(get_data_source)],
    llm: Annotated[LlmPort, Depends(get_llm)],
    _principal: Annotated[Principal, Depends(get_principal)],
) -> BriefOut:
    brief = await build_brief(source, llm, client_id)
    latest = brief.latest_snapshot
    return BriefOut(
        client=ClientCardOut(
            id=brief.client.id,
            kcr_code=brief.client.kcr_code,
            organization_name=brief.client.organization_name,
            inn=brief.client.inn,
            client_type=brief.client.client_type.value if brief.client.client_type else None,
            stage=brief.client.stage.value,
            assignee_username=brief.client.assignee_username,
            next_contact_at=brief.client.next_contact_at,
            created_at=brief.client.created_at,
            contact_person=brief.client.contact_person,
            contact_phone=brief.client.contact_phone,
            contact_email=brief.client.contact_email,
        ),
        documents=(
            SnapshotOut(
                reporting_period=latest.reporting_period,
                snapshot_date=latest.snapshot_date,
                total_documents=latest.total_documents,
                not_transferred_documents=latest.not_transferred_documents,
                percent_not_transferred=latest.untransferred_percent,
            )
            if latest
            else None
        ),
        dynamics=(
            DynamicsOut(category=brief.dynamics_category, description=brief.dynamics_description or "")
            if brief.dynamics_category
            else None
        ),
        dynamics_history=[
            PeriodCategoryOut(period=period, category=category) for period, category in brief.period_categories
        ],
        recent_events=[
            EventOut(
                id=event.id,
                type=event.type.value,
                author_username=event.author_username,
                created_at=event.created_at,
                call_result=event.call_result.value if event.call_result else None,
                agreement=event.agreement,
                client_deadline=event.client_deadline,
                next_contact_at=event.next_contact_at,
                comment=event.comment,
            )
            for event in brief.recent_events
        ],
        claims=[
            ClaimOut(
                number=claim.number,
                claim_date=claim.claim_date,
                status=claim.status.value,
                sanctions=[SanctionOut(name=s.name, status=s.status.value) for s in claim.sanctions],
            )
            for claim in brief.claims
        ],
        next_action_date=brief.next_action_date,
        summary=brief.summary,
    )

"""Read-only httpx adapter over the kcr-backend REST API (DataSourcePort).

The adapter performs GET requests only — the read-only invariant of ТЗ is
enforced structurally: there is no method here that could mutate anything.
Requests carry the user's access token verbatim, so kcr-backend applies its own
permission rules (operator sees own, admin sees all).
"""

from __future__ import annotations

import logging
from typing import Any

import httpx

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
from kcr_ml.exceptions import (
    AccessDeniedError,
    AuthenticationRequiredError,
    BackendUnavailableError,
    ResourceNotFoundError,
)

logger = logging.getLogger(__name__)

_REQUEST_TIMEOUT = httpx.Timeout(15.0)


class BackendApiDataSource:
    """Implements DataSourcePort on top of the backend REST API."""

    def __init__(self, base_url: str, token: str) -> None:
        self._client = httpx.AsyncClient(
            base_url=base_url.rstrip("/"),
            headers={"Authorization": f"Bearer {token}"},
            timeout=_REQUEST_TIMEOUT,
        )

    async def aclose(self) -> None:
        await self._client.aclose()

    # ----------------------------------------------------------------------------------
    # HTTP plumbing
    # ----------------------------------------------------------------------------------

    async def _get(self, path: str, params: dict[str, Any] | None = None) -> httpx.Response:
        try:
            response = await self._client.get(path, params=self._clean(params))
        except httpx.HTTPError as error:
            raise BackendUnavailableError(f"kcr-backend request failed: {error.__class__.__name__}") from error
        if response.status_code == 200:
            return response
        if response.status_code == 401:
            raise AuthenticationRequiredError("kcr-backend rejected the access token")
        if response.status_code == 403:
            raise AccessDeniedError("kcr-backend denied access to the requested data")
        if response.status_code == 404:
            raise ResourceNotFoundError(f"kcr-backend has no resource at {path}")
        raise BackendUnavailableError(f"kcr-backend returned HTTP {response.status_code} for {path}")

    @staticmethod
    def _clean(params: dict[str, Any] | None) -> dict[str, Any] | None:
        if params is None:
            return None
        return {key: value for key, value in params.items() if value is not None}

    @staticmethod
    def _json(response: httpx.Response) -> Any:
        try:
            return response.json()
        except ValueError as error:
            raise BackendUnavailableError("kcr-backend returned a non-JSON body") from error

    @staticmethod
    def _parse(raw: Any, mapper: Any, what: str) -> Any:
        try:
            return mapper(raw)
        except (KeyError, TypeError, ValueError) as error:
            logger.warning("Contract mismatch in %s: %s", what, error)
            raise BackendUnavailableError(f"kcr-backend response contract mismatch in {what}") from error

    # ----------------------------------------------------------------------------------
    # Parsers (strict: a contract mismatch is an explicit error, never partial data)
    # ----------------------------------------------------------------------------------

    @staticmethod
    def _parse_client(raw: dict[str, Any]) -> ClientView:
        return ClientView(
            id=int(raw["id"]),
            kcr_code=str(raw["kcrCode"]),
            inn=str(raw["inn"]),
            organization_name=str(raw["organizationName"]),
            kpp=raw.get("kpp"),
            internal_id=raw.get("internalId"),
            client_type=ClientType(raw["clientType"]) if raw.get("clientType") else None,
            kcr_active=raw.get("kcrActive"),
            kcr_blocked=raw.get("kcrBlocked"),
            tariff=raw.get("tariff"),
            ekb_travel_time=raw.get("ekbTravelTime"),
            manager=raw.get("manager"),
            flags=tuple(raw.get("flags") or ()),
            contact_person=raw.get("contactPerson"),
            contact_phone=raw.get("contactPhone"),
            contact_email=raw.get("contactEmail"),
            stage=WorkStage(raw["stage"]) if raw.get("stage") else WorkStage.NOT_IN_WORK,
            assignee_id=raw.get("assigneeId"),
            assignee_username=raw.get("assigneeUsername"),
            next_contact_at=_parse_date(raw.get("nextContactAt")),
            created_at=_parse_datetime(raw.get("createdAt")),
        )

    @staticmethod
    def _parse_snapshot(raw: dict[str, Any]) -> SnapshotView:
        return SnapshotView(
            id=int(raw["id"]),
            client_id=int(raw["clientId"]),
            reporting_period=str(raw["reportingPeriod"]),
            snapshot_date=_parse_date(raw["snapshotDate"]),
            total_documents=raw.get("totalDocuments"),
            not_transferred_documents=int(raw["notTransferredDocuments"]),
            percent_not_transferred=raw.get("percentNotTransferred"),
        )

    @staticmethod
    def _parse_event(raw: dict[str, Any]) -> EventView:
        return EventView(
            id=int(raw["id"]),
            client_id=int(raw["clientId"]),
            type=ClientEventType(raw["type"]),
            author_id=raw.get("authorId"),
            author_username=str(raw["authorUsername"]),
            created_at=_parse_datetime(raw["createdAt"]),
            comment=raw.get("comment"),
            call_result=CallResult(raw["callResult"]) if raw.get("callResult") else None,
            agreement=raw.get("agreement"),
            client_deadline=_parse_date(raw.get("clientDeadline")),
            next_contact_at=_parse_date(raw.get("nextContactAt")),
            record_url=raw.get("recordUrl"),
        )

    @staticmethod
    def _parse_claim(raw: dict[str, Any]) -> ClaimView:
        return ClaimView(
            id=int(raw["id"]),
            client_id=int(raw["clientId"]),
            number=str(raw["number"]),
            claim_date=_parse_date(raw["claimDate"]),
            incident_number=raw.get("incidentNumber"),
            received_by_client_at=_parse_date(raw.get("receivedByClientAt")),
            status=ClaimStatus(raw["status"]) if raw.get("status") else ClaimStatus.NEW,
            comment=raw.get("comment"),
            created_at=_parse_datetime(raw.get("createdAt")),
            sanctions=tuple(
                SanctionView(
                    id=int(item["id"]),
                    claim_id=int(item["claimId"]),
                    name=str(item["name"]),
                    status=SanctionStatus(item["status"]) if item.get("status") else SanctionStatus.PLANNED,
                    created_at=_parse_datetime(item.get("createdAt")),
                )
                for item in raw.get("sanctions") or ()
            ),
        )

    @staticmethod
    def _parse_page(raw: dict[str, Any], item_mapper: Any, what: str) -> Page:
        content = tuple(BackendApiDataSource._parse(item, item_mapper, what) for item in raw["content"])
        return Page(
            content=content,
            page=int(raw["page"]),
            size=int(raw["size"]),
            total_elements=int(raw["totalElements"]),
            total_pages=int(raw["totalPages"]),
        )

    # ----------------------------------------------------------------------------------
    # DataSourcePort
    # ----------------------------------------------------------------------------------

    async def list_clients(self, query: ClientQuery) -> Page:
        response = await self._get(
            "/api/v1/clients",
            {
                "search": query.search,
                "clientType": query.client_type.value if query.client_type else None,
                "stage": query.stage.value if query.stage else None,
                "assigneeId": query.assignee_id,
                "unassigned": query.unassigned,
                "page": query.page,
                "size": query.size,
                "sortBy": query.sort_by,
                "sortDir": query.sort_dir,
            },
        )
        return self._parse_page(self._json(response), self._parse_client, "clients page")

    async def get_client(self, client_id: int) -> ClientView | None:
        try:
            response = await self._get(f"/api/v1/clients/{client_id}")
        except ResourceNotFoundError:
            return None
        return self._parse(self._json(response), self._parse_client, "client")

    async def list_snapshots(self, client_id: int, reporting_period: str | None = None) -> list[SnapshotView]:
        response = await self._get(f"/api/v1/clients/{client_id}/snapshots", {"reportingPeriod": reporting_period})
        raw = self._json(response)
        if not isinstance(raw, list):
            raise BackendUnavailableError("kcr-backend snapshots response is not a list")
        return [self._parse(item, self._parse_snapshot, "snapshot") for item in raw]

    async def list_events(self, client_id: int, page: int = 0, size: int = 20) -> Page:
        response = await self._get(f"/api/v1/clients/{client_id}/events", {"page": page, "size": size})
        return self._parse_page(self._json(response), self._parse_event, "events page")

    async def list_claims(self, client_id: int) -> list[ClaimView]:
        response = await self._get(f"/api/v1/clients/{client_id}/claims")
        raw = self._json(response)
        if not isinstance(raw, list):
            raise BackendUnavailableError("kcr-backend claims response is not a list")
        return [self._parse(item, self._parse_claim, "claim") for item in raw]

    async def today_worklist(self, page: int = 0, size: int = 20) -> Page:
        response = await self._get("/api/v1/worklist/today", {"page": page, "size": size})
        return self._parse_page(self._json(response), self._parse_client, "worklist page")


def _parse_date(value: str | None) -> Any:
    if value is None:
        return None
    from datetime import date

    return date.fromisoformat(value)


def _parse_datetime(value: str | None) -> Any:
    if value is None:
        return None
    from datetime import datetime

    return datetime.fromisoformat(value.replace("Z", "+00:00"))

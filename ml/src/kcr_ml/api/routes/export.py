"""UC-5: xlsx export — REST route (admin-only, §2)."""

from __future__ import annotations

from datetime import UTC, date, datetime
from typing import Annotated

from fastapi import APIRouter, Depends, Response

from kcr_ml.api.deps import get_data_source, get_exporter, get_settings, get_today, require_admin
from kcr_ml.api.schemas.rest import ExportRequest, TablePayload
from kcr_ml.application.export_service import build_tables, render
from kcr_ml.application.ports import DataSourcePort, ExcelExporterPort
from kcr_ml.config import Settings
from kcr_ml.domain.model import Column, Principal, TabularData
from kcr_ml.exceptions import ValidationFailedError

router = APIRouter(prefix="/api/v1/ai", tags=["export"])

XLSX_MEDIA_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
MAX_EXPORT_ROWS = 10_000


def _payload_to_table(payload: TablePayload) -> TabularData:
    if len(payload.rows) > MAX_EXPORT_ROWS:
        raise ValidationFailedError(f"Export payload exceeds {MAX_EXPORT_ROWS} rows")
    if any(len(row) != len(payload.columns) for row in payload.rows):
        raise ValidationFailedError("Export row width does not match columns")
    return TabularData(
        title=payload.title,
        columns=tuple(Column(key=str(index), label=label) for index, label in enumerate(payload.columns)),
        rows=tuple(tuple(row) for row in payload.rows),
    )


@router.post("/export/xlsx")
async def export_xlsx(
    request: ExportRequest,
    source: Annotated[DataSourcePort, Depends(get_data_source)],
    exporter: Annotated[ExcelExporterPort, Depends(get_exporter)],
    settings: Annotated[Settings, Depends(get_settings)],
    today: Annotated[date, Depends(get_today)],
    _admin: Annotated[Principal, Depends(require_admin)],
) -> Response:
    table = _payload_to_table(request.table) if request.table is not None else None
    if request.kind.value == "table" and table is None:
        raise ValidationFailedError("kind='table' requires a table payload")
    tables = await build_tables(
        request.kind,
        source,
        table=table,
        today=today,
        max_clients=settings.ai_max_scan_clients,
    )
    content = render(exporter, tables)
    stamp = datetime.now(UTC).strftime("%Y%m%d_%H%M%S")
    filename = f"{request.kind.value}_{stamp}.xlsx"
    return Response(
        content=content,
        media_type=XLSX_MEDIA_TYPE,
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )

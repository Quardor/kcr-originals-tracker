"""UC-5: Excel export of selections and analytics reports (ARCHITECTURE.md §6).

Analytics kinds are recomputed deterministically from the data source; the
generic ``table`` kind renders a table the client has already received (e.g. a
prioritization result or a chat data message) — numbers stay source-owned
either way. Rendering itself lives behind ``ExcelExporterPort``.
"""

from __future__ import annotations

from datetime import date
from enum import StrEnum

from kcr_ml.application import analytics_service
from kcr_ml.application.ports import DataSourcePort, ExcelExporterPort
from kcr_ml.domain.model import Column, DynamicsCategory, TabularData, WorkStage

CATEGORY_LABELS = {
    DynamicsCategory.NO_DEBT: "Все переданы",
    DynamicsCategory.FIRST_OCCURRENCE: "Впервые появились",
    DynamicsCategory.DECREASING: "Уменьшается",
    DynamicsCategory.UNCHANGED_OR_GROWING: "Не меняется/растёт",
    DynamicsCategory.REAPPEARED: "Повторное появление",
    DynamicsCategory.PERSISTENT: "Сохраняется длительно",
    DynamicsCategory.NEWCOMER_RISK: "Долг у новичка",
}


class ExportKind(StrEnum):
    TABLE = "table"
    ANALYTICS_SUMMARY = "analytics_summary"
    ANALYTICS_DYNAMICS = "analytics_dynamics"
    ANALYTICS_TOP_DEBT = "analytics_top_debt"
    ANALYTICS_OPERATORS = "analytics_operators"


def _summary_tables(data: dict[str, object]) -> list[TabularData]:
    totals = data["totals"]
    categories_table = TabularData(
        title="Категории динамики",
        columns=(Column("category", "Категория"), Column("count", "Клиентов")),
        rows=tuple(
            (category.value, data["by_dynamics_category"][category.value])  # type: ignore[index]
            for category in DynamicsCategory
        ),
    )
    stages_table = TabularData(
        title="Этапы работы",
        columns=(Column("stage", "Этап"), Column("count", "Клиентов")),
        rows=tuple(
            (stage.value, data["by_stage"][stage.value])  # type: ignore[index]
            for stage in WorkStage
        ),
    )
    totals_table = TabularData(
        title="Итоги",
        columns=(Column("metric", "Показатель"), Column("value", "Значение")),
        rows=(
            ("Клиентов всего", totals["clients"]),  # type: ignore[index]
            ("С непереданными документами", totals["with_debt"]),  # type: ignore[index]
            ("Непереданных документов", totals["not_transferred_documents"]),  # type: ignore[index]
        ),
    )
    return [categories_table, stages_table, totals_table]


def _dynamics_table(data: dict[str, object]) -> list[TabularData]:
    columns = (
        Column("period", "Период"),
        *(Column(key, key.replace("_", " ")) for key in analytics_service.DYNAMICS_SERIES_KEYS),
    )
    rows = tuple(
        tuple(row[key] for key in ("period", *analytics_service.DYNAMICS_SERIES_KEYS))  # type: ignore[misc]
        for row in data["series"]  # type: ignore[union-attr]
    )
    return [TabularData(title="Динамика по периодам", columns=columns, rows=rows)]


def _top_debt_table(data: dict[str, object]) -> list[TabularData]:
    columns = (
        Column("kcr_code", "Код KCR"),
        Column("organization_name", "Организация"),
        Column("assignee", "Ответственный"),
        Column("reporting_period", "Период"),
        Column("not_transferred", "Непередано"),
        Column("total", "Всего"),
        Column("percent", "% непередано"),
    )
    rows = tuple(
        (
            item["kcr_code"],
            item["organization_name"],
            item["assignee_username"],
            item["reporting_period"],
            item["not_transferred_documents"],
            item["total_documents"],
            item["percent_not_transferred"],
        )
        for item in data["items"]  # type: ignore[union-attr]
    )
    return [TabularData(title="Топ по доле непереданных", columns=columns, rows=rows)]


def _operators_table(data: dict[str, object]) -> list[TabularData]:
    columns = (
        Column("username", "Оператор"),
        Column("in_work", "В работе"),
        Column("due_today", "Сегодня к контакту"),
    )
    rows = tuple(
        (item["username"], item["in_work"], item["due_today"])
        for item in data["operators"]  # type: ignore[union-attr]
    )
    totals = data["totals"]  # type: ignore[assignment]
    rows = (*rows, ("— без ответственного (пул)", None, totals["due_today_unassigned"]))
    return [TabularData(title="Нагрузка операторов", columns=columns, rows=rows)]


async def build_tables(
    kind: ExportKind,
    source: DataSourcePort,
    *,
    table: TabularData | None = None,
    today: date,
    limit: int = 10,
    max_clients: int = 500,
) -> list[TabularData]:
    """Collect the tables of the requested export kind."""
    if kind == ExportKind.TABLE:
        if table is None:
            raise ValueError("table payload is required for kind='table'")
        return [table]
    if kind == ExportKind.ANALYTICS_SUMMARY:
        return _summary_tables(await analytics_service.summary(source, max_clients))
    if kind == ExportKind.ANALYTICS_DYNAMICS:
        return _dynamics_table(await analytics_service.dynamics_series(source, max_clients))
    if kind == ExportKind.ANALYTICS_TOP_DEBT:
        return _top_debt_table(await analytics_service.top_debt(source, limit, max_clients))
    return _operators_table(await analytics_service.operators(source, today, max_clients))


def render(exporter: ExcelExporterPort, tables: list[TabularData]) -> bytes:
    """Render tables into an xlsx workbook via the export port."""
    return exporter.export(tables)

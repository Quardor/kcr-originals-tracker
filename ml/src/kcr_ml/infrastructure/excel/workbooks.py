"""xlsx rendering behind ExcelExporterPort: pandas builds sheets, openpyxl styles.

Ready-made libraries only (ТЗ requirement — no hand-rolled xlsx parsing/writing).
One exported table becomes one worksheet with a frozen bold header and
sane column widths.
"""

from __future__ import annotations

import io
import re
from collections.abc import Sequence

import pandas as pd
from openpyxl.styles import Font
from openpyxl.utils import get_column_letter

from kcr_ml.domain.model import TabularData

MAX_COLUMN_WIDTH = 60
INVALID_SHEET_CHARS = re.compile(r"[\[\]:*?/\\]")


class OpenPyxlExporter:
    """Implements ExcelExporterPort with pandas + openpyxl."""

    def export(self, tables: Sequence[TabularData]) -> bytes:
        buffer = io.BytesIO()
        used_names: set[str] = set()
        with pd.ExcelWriter(buffer, engine="openpyxl") as writer:
            for table in tables:
                frame = pd.DataFrame(
                    {column.label: [row[index] for row in table.rows] for index, column in enumerate(table.columns)}
                )
                sheet = self._sheet_name(table.title, used_names)
                frame.to_excel(writer, sheet_name=sheet, index=False)
                self._style(writer.sheets[sheet], table)
        return buffer.getvalue()

    @staticmethod
    def _sheet_name(title: str, used: set[str]) -> str:
        cleaned = INVALID_SHEET_CHARS.sub(" ", title).strip() or "Лист"
        base = cleaned[:31]
        name = base
        suffix = 1
        while name in used:
            tail = f" ({suffix})"
            name = base[: 31 - len(tail)] + tail
            suffix += 1
        used.add(name)
        return name

    @staticmethod
    def _style(sheet: object, table: TabularData) -> None:
        header_font = Font(bold=True)
        for cell in next(iter(sheet.rows)):  # type: ignore[union-attr]
            cell.font = header_font
        sheet.freeze_panes = "A2"  # type: ignore[union-attr]
        for index, column in enumerate(table.columns, start=1):
            width = max([len(str(column.label))] + [len(str(row[index - 1])) for row in table.rows] or [0])
            letter = get_column_letter(index)
            sheet.column_dimensions[letter].width = min(max(width + 2, 8), MAX_COLUMN_WIDTH)  # type: ignore[index]


def build_exporter() -> OpenPyxlExporter:
    """Factory for dependency wiring."""
    return OpenPyxlExporter()

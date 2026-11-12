"""Integration: xlsx export endpoint (UC-5)."""

from __future__ import annotations

import io
import zipfile

from fastapi.testclient import TestClient

XLSX_MAGIC = b"PK"


def test_export_analytics_summary(client: TestClient, admin_headers: dict[str, str]) -> None:
    response = client.post("/api/v1/ai/export/xlsx", json={"kind": "analytics_summary"}, headers=admin_headers)
    assert response.status_code == 200
    assert response.headers["content-type"].startswith(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    )
    assert "attachment" in response.headers["content-disposition"]
    assert response.content[:2] == XLSX_MAGIC
    with zipfile.ZipFile(io.BytesIO(response.content)) as book:
        assert len(book.namelist()) >= 3  # one sheet per table + workbook parts


def test_export_table_payload_roundtrip(client: TestClient, admin_headers: dict[str, str]) -> None:
    payload = {
        "kind": "table",
        "table": {
            "title": "Приоритетная выборка",
            "columns": ["Код KCR", "Организация", "Балл"],
            "rows": [["kcr_0001", "Альфа-Сервис", 43], ["kcr_0010", "Ладога", 32]],
        },
    }
    response = client.post("/api/v1/ai/export/xlsx", json=payload, headers=admin_headers)
    assert response.status_code == 200
    assert response.content[:2] == XLSX_MAGIC


def test_export_table_kind_requires_payload(client: TestClient, admin_headers: dict[str, str]) -> None:
    response = client.post("/api/v1/ai/export/xlsx", json={"kind": "table"}, headers=admin_headers)
    assert response.status_code == 400
    assert response.json()["code"] == "VALIDATION_FAILED"


def test_export_row_width_mismatch_rejected(client: TestClient, admin_headers: dict[str, str]) -> None:
    payload = {
        "kind": "table",
        "table": {"title": "X", "columns": ["a", "b"], "rows": [[1]]},
    }
    response = client.post("/api/v1/ai/export/xlsx", json=payload, headers=admin_headers)
    assert response.status_code == 400

package ru.quard0r.kcrbackend.imports.dto;

import ru.quard0r.kcrbackend.imports.ParseIssue;

import java.util.List;

/**
 * Validation of a snapshot import file: matched rows with the resolved document totals, KCR
 * codes that have no client in the database, and parse issues. Nothing is written
 * (ARCHITECTURE.md §11.4).
 */
public record SnapshotImportPreview(
        List<SnapshotPreviewRow> rows,
        List<String> unmatchedKcrCodes,
        List<ParseIssue> issues
) {
}

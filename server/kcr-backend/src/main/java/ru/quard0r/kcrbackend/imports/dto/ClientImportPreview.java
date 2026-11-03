package ru.quard0r.kcrbackend.imports.dto;

import ru.quard0r.kcrbackend.imports.ParseIssue;

import java.util.List;

/**
 * Validation of a client import file against the current database state; nothing is written
 * (ARCHITECTURE.md §11.4). Rows with parse issues are neither in {@code toCreate} nor
 * {@code toUpdate} — they are reported in {@code issues} with the reason.
 */
public record ClientImportPreview(
        List<ClientImportRow> toCreate,
        List<ClientImportRow> toUpdate,
        List<ParseIssue> issues
) {
}

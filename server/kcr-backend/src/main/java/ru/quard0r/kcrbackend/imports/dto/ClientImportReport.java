package ru.quard0r.kcrbackend.imports.dto;

import ru.quard0r.kcrbackend.imports.ParseIssue;

import java.util.List;

/**
 * Outcome of a client import apply: how many clients were created or updated and the rows
 * skipped with a reason (ARCHITECTURE.md §11.4).
 */
public record ClientImportReport(
        int created,
        int updated,
        List<ParseIssue> issues
) {
}

package ru.quard0r.kcrbackend.imports.dto;

import ru.quard0r.kcrbackend.imports.ParseIssue;

import java.util.List;

public record SnapshotImportReport(
        int created,
        List<String> unmatchedKcrCodes,
        List<String> skippedSheets,
        List<ParseIssue> issues
) {
}

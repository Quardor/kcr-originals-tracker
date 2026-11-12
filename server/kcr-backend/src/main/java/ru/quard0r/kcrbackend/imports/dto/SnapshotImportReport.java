package ru.quard0r.kcrbackend.imports.dto;

import ru.quard0r.kcrbackend.imports.ParseIssue;

import java.util.List;

/**
 * Outcome of a snapshot import apply: how many snapshots were created, the KCR codes without
 * a matching client (they go into the report; the downloadable file of §11.4 is deferred),
 * sheets of other reporting periods skipped by the period filter, and the rows skipped due
 * to parse issues.
 */
public record SnapshotImportReport(
        int created,
        List<String> unmatchedKcrCodes,
        List<String> skippedSheets,
        List<ParseIssue> issues
) {
}

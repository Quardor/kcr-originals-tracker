package ru.quard0r.kcrbackend.imports.dto;

import java.time.LocalDate;

/**
 * Command issued by the Excel import adapter; reportingPeriod is the period selected by the
 * caller (matched against sheet names, ARCHITECTURE.md §11.4), snapshotDate is supplied by
 * the caller (the export file carries it only in its name). totalDocuments may be null —
 * the upsert service then inherits it from the previous snapshot of the same period
 * (ARCHITECTURE.md §4).
 */
public record SnapshotCreateCommand(
        String kcrCode,
        String reportingPeriod,
        LocalDate snapshotDate,
        Integer totalDocuments,
        int notTransferredDocuments
) {
}

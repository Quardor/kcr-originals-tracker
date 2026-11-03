package ru.quard0r.kcrbackend.imports.dto;

import java.time.LocalDate;

/**
 * One matched row of a snapshot import preview. {@code totalInFile} is what the export carries
 * (may be null); {@code totalResolved} is the value the apply will store — the file value or
 * the one inherited from the previous snapshot of the same client and period (ARCHITECTURE.md §4).
 */
public record SnapshotPreviewRow(
        String kcrCode,
        String reportingPeriod,
        LocalDate snapshotDate,
        Integer totalInFile,
        Integer totalResolved,
        int notTransferredDocuments
) {
}

package ru.quard0r.kcrbackend.imports.dto;

import java.time.LocalDate;

public record SnapshotPreviewRow(
        String kcrCode,
        String reportingPeriod,
        LocalDate snapshotDate,
        Integer totalInFile,
        Integer totalResolved,
        int notTransferredDocuments
) {
}

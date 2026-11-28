package ru.quard0r.kcrbackend.imports.dto;

import java.time.LocalDate;

public record SnapshotCreateCommand(
        String kcrCode,
        String reportingPeriod,
        LocalDate snapshotDate,
        Integer totalDocuments,
        int notTransferredDocuments
) {
}

package ru.quard0r.kcrbackend.dto.response;

import lombok.Builder;
import ru.quard0r.kcrbackend.model.DocumentTransferSnapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

@Builder
public record SnapshotResponse (
        Long id,
        Long clientId,
        String reportingPeriod,
        LocalDate snapshotDate,
        Integer totalDocuments,
        Integer notTransferredDocuments,
        Double percentNotTransferred
){
    public static SnapshotResponse from(DocumentTransferSnapshot snapshot, Long clientId) {
        Double percentNotTransferred = null;
        if (snapshot.getTotalDocuments() != null) {
            percentNotTransferred = BigDecimal.valueOf(snapshot.getNotTransferredDocuments() * 100.0)
                    .divide(BigDecimal.valueOf(snapshot.getTotalDocuments()), 2, RoundingMode.HALF_UP)
                    .doubleValue();
        }
        return SnapshotResponse.builder()
                .id(snapshot.getId())
                .clientId(clientId)
                .reportingPeriod(snapshot.getReportingPeriod())
                .snapshotDate(snapshot.getSnapshotDate())
                .totalDocuments(snapshot.getTotalDocuments())
                .notTransferredDocuments(snapshot.getNotTransferredDocuments())
                .percentNotTransferred(percentNotTransferred)
                .build();
    }
}

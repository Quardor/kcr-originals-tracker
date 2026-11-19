package ru.quard0r.kcrbackend.snapshots.dto;

import lombok.Builder;
import ru.quard0r.kcrbackend.snapshots.model.DynamicsCategory;

import java.time.LocalDate;


@Builder
public record ClientDebtInfo(
        Integer totalDocuments,
        Integer notTransferredDocuments,
        Double percentNotTransferred,
        DynamicsCategory dynamicsCategory,
        Integer previousNotTransferred,
        String reportingPeriod,
        LocalDate snapshotDate
) {
    public static ClientDebtInfo empty()
    {
        return ClientDebtInfo.builder()
                .totalDocuments(null)
                .notTransferredDocuments(0)
                .percentNotTransferred(0.0)
                .dynamicsCategory(DynamicsCategory.NO_DEBT)
                .previousNotTransferred(null)
                .reportingPeriod(null)
                .snapshotDate(null)
                .build();
    }
}

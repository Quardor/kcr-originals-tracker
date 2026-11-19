package ru.quard0r.kcrbackend.clients.dto;

import lombok.Builder;
import ru.quard0r.kcrbackend.clients.model.Client;
import ru.quard0r.kcrbackend.clients.model.ClientType;
import ru.quard0r.kcrbackend.clients.model.WorkStage;
import ru.quard0r.kcrbackend.snapshots.dto.ClientDebtInfo;
import ru.quard0r.kcrbackend.snapshots.model.DynamicsCategory;

import java.time.Instant;
import java.time.LocalDate;

@Builder
public record CallListClientResponse(
        Long clientId,
        String kcrCode,
        String inn,
        String kpp,
        String organizationName,
        ClientType clientType,
        WorkStage stage,
        Long assigneeId,
        String assigneeUsername,
        LocalDate nextContactAt,
        Integer totalDocuments,
        Integer notTransferredDocuments,
        Double percentNotTransferred,
        DynamicsCategory dynamicsCategory,
        Integer previousNotTransferred,
        String reportingPeriod,
        LocalDate snapshotDate,
        Instant addedAt,
        String addedByUsername
) {
    public static CallListClientResponse from(Client client, ClientDebtInfo debtInfo,
                                              Instant addedAt, String addedByUsername)
    {
        return CallListClientResponse.builder()
                .clientId(client.getId())
                .kcrCode(client.getKcrCode())
                .inn(client.getInn())
                .kpp(client.getKpp())
                .organizationName(client.getOrganizationName())
                .clientType(client.getClientType())
                .stage(client.getStage())
                .assigneeId(client.getAssignee() != null ? client.getAssignee().getId() : null)
                .assigneeUsername(client.getAssignee() != null ? client.getAssignee().getUsername() : null)
                .nextContactAt(client.getNextContactAt())
                .totalDocuments(debtInfo != null ? debtInfo.totalDocuments() : null)
                .notTransferredDocuments(debtInfo != null ? debtInfo.notTransferredDocuments() : 0)
                .percentNotTransferred(debtInfo != null ? debtInfo.percentNotTransferred() : null)
                .dynamicsCategory(debtInfo != null ? debtInfo.dynamicsCategory() : null)
                .previousNotTransferred(debtInfo != null ? debtInfo.previousNotTransferred() : null)
                .reportingPeriod(debtInfo != null ? debtInfo.reportingPeriod() : null)
                .snapshotDate(debtInfo != null ? debtInfo.snapshotDate() : null)
                .addedAt(addedAt)
                .addedByUsername(addedByUsername)
                .build();
    }
}

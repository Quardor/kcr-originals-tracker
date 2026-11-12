package ru.quard0r.kcrbackend.clients.dto;

import lombok.Builder;
import ru.quard0r.kcrbackend.clients.model.Client;
import ru.quard0r.kcrbackend.clients.model.ClientType;
import ru.quard0r.kcrbackend.clients.model.WorkStage;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Builder
public record ClientResponse (
        Long id,
        String kcrCode,
        String inn,
        String kpp,
        String organizationName,
        String internalId,
        ClientType clientType,
        Boolean kcrActive,
        Boolean kcrBlocked,
        String tariff,
        String ekbTravelTime,
        String manager,
        List<String> flags,
        String contactPerson,
        String contactPhone,
        String contactEmail,
        WorkStage stage,
        Long assigneeId,
        String assigneeUsername,
        LocalDate nextContactAt,
        Instant createdAt
){
    public static ClientResponse from(Client client) {
        return ClientResponse.builder()
                .id(client.getId())
                .kcrCode(client.getKcrCode())
                .inn(client.getInn())
                .kpp(client.getKpp())
                .organizationName(client.getOrganizationName())
                .internalId(client.getInternalId())
                .clientType(client.getClientType())
                .kcrActive(client.getKcrActive())
                .kcrBlocked(client.getKcrBlocked())
                .tariff(client.getTariff())
                .ekbTravelTime(client.getEkbTravelTime())
                .manager(client.getManager())
                .flags(client.getFlags())
                .contactPerson(client.getContactPerson())
                .contactPhone(client.getContactPhone())
                .contactEmail(client.getContactEmail())
                .stage(client.getStage())
                .assigneeId(client.getAssignee() == null ? null : client.getAssignee().getId())
                .assigneeUsername(client.getAssignee() == null ? null : client.getAssignee().getUsername())
                .nextContactAt(client.getNextContactAt())
                .createdAt(client.getCreatedAt())
                .build();
    }
}

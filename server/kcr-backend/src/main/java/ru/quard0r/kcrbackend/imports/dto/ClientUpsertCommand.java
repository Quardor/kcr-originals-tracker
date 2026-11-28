package ru.quard0r.kcrbackend.imports.dto;

import lombok.Builder;
import ru.quard0r.kcrbackend.clients.model.ClientType;

import java.util.List;

@Builder
public record ClientUpsertCommand(
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
        String contactEmail
) {
}

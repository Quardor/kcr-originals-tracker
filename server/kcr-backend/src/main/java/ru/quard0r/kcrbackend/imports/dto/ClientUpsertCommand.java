package ru.quard0r.kcrbackend.imports.dto;

import lombok.Builder;
import ru.quard0r.kcrbackend.model.ClientType;

import java.util.List;

/**
 * Command issued by the Excel import adapter and consumed by the client upsert service;
 * a future API integration will issue the same command, so the Excel parser stays a thin
 * adapter (ARCHITECTURE.md §11.6). Fields mirror the client card, absent columns come as null.
 */
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

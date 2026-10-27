package ru.quard0r.kcrbackend.dto.response;

import lombok.Builder;
import ru.quard0r.kcrbackend.model.Client;
import ru.quard0r.kcrbackend.model.ClientType;

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
        String contactEmail
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
                .build();
    }
}

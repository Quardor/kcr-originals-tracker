package ru.quard0r.kcrbackend.clients.events.dto;

import lombok.Builder;
import ru.quard0r.kcrbackend.clients.events.Sanction;
import ru.quard0r.kcrbackend.clients.events.SanctionStatus;

import java.time.Instant;

@Builder
public record SanctionResponse (
        Long id,
        Long claimId,
        String name,
        SanctionStatus status,
        Instant createdAt
){
    public static SanctionResponse from(Sanction sanction) {
        return SanctionResponse.builder()
                .id(sanction.getId())
                .claimId(sanction.getClaim().getId())
                .name(sanction.getName())
                .status(sanction.getStatus())
                .createdAt(sanction.getCreatedAt())
                .build();
    }
}

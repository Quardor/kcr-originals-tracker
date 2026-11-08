package ru.quard0r.kcrbackend.clients.events.dto;

import lombok.Builder;
import ru.quard0r.kcrbackend.clients.events.Claim;
import ru.quard0r.kcrbackend.clients.events.ClaimStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Builder
public record ClaimResponse (
        Long id,
        Long clientId,
        String number,
        LocalDate claimDate,
        String incidentNumber,
        LocalDate receivedByClientAt,
        ClaimStatus status,
        String comment,
        Instant createdAt,
        List<SanctionResponse> sanctions
){
    public static ClaimResponse from(Claim claim, List<SanctionResponse> sanctions) {
        return ClaimResponse.builder()
                .id(claim.getId())
                .clientId(claim.getClient().getId())
                .number(claim.getNumber())
                .claimDate(claim.getClaimDate())
                .incidentNumber(claim.getIncidentNumber())
                .receivedByClientAt(claim.getReceivedByClientAt())
                .status(claim.getStatus())
                .comment(claim.getComment())
                .createdAt(claim.getCreatedAt())
                .sanctions(sanctions)
                .build();
    }
}

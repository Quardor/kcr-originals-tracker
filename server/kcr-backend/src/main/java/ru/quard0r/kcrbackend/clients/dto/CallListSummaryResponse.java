package ru.quard0r.kcrbackend.clients.dto;

import lombok.Builder;

import java.time.Instant;
import java.util.List;

@Builder
public record CallListSummaryResponse(
        int totalClients,
        Instant updatedAt,
        String updatedByUsername,
        List<CallListClientResponse> clients
) {}

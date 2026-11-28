package ru.quard0r.kcrbackend.ai.dto;

import lombok.Builder;

@Builder
public record ChatTicketResponse(
        String ticket,
        long expiresInSeconds
) {
}

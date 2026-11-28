package ru.quard0r.kcrbackend.ai;

import java.time.Instant;

record ChatTicket(
        String accessToken,
        Instant expiresAt
) {
}

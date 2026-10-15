package ru.quard0r.kcrbackend.dto.response;

import lombok.Builder;

@Builder
public record ErrorResponse (
        String message,
        long timestamp
) {

    public ErrorResponse(String message) {
        this(message, System.currentTimeMillis());
    }
}

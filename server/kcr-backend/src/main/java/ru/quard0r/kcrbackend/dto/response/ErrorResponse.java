package ru.quard0r.kcrbackend.dto.response;

import lombok.Builder;
import ru.quard0r.kcrbackend.exception.ErrorCode;

import java.time.Instant;

@Builder
public record ErrorResponse (
        String code,
        String message,
        Instant timestamp
) {

    public ErrorResponse(ErrorCode errorCode) {
        this(errorCode.name(), errorCode.getMessage(), Instant.now());
    }

    public ErrorResponse(ErrorCode errorCode, String message) {
        this(errorCode.name(), message, Instant.now());
    }
}

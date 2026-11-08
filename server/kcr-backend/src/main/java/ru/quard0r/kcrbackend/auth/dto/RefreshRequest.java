package ru.quard0r.kcrbackend.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record RefreshRequest (
        @NotBlank(message = "Refresh token must not be blank")
        String refreshToken
) {
}

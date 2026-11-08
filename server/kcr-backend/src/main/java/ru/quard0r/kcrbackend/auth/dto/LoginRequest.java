package ru.quard0r.kcrbackend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import ru.quard0r.kcrbackend.common.UserIdentityNormalizer;

public record LoginRequest (
        @NotBlank(message = "Username must not be blank")
        String username,

        @NotBlank(message = "Password must not be blank")
        String password
) {

    public LoginRequest {
        username = UserIdentityNormalizer.username(username);
    }
}

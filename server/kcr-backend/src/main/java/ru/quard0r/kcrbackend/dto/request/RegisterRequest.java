package ru.quard0r.kcrbackend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest (
        @NotBlank(message = "Username must not be blank")
        @Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
        String username,

        @NotBlank(message = "Password must not be blank")
        @Size(min = 8, max = 100, message = "Password must be between 8 and 100 characters")
        String password,

        @Email(message = "Email must be valid")
        String email
) {
}

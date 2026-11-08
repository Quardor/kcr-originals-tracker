package ru.quard0r.kcrbackend.auth.dto;

import lombok.Builder;

@Builder
public record AuthResponse (
        String accessToken,
        String refreshToken
){
}

package ru.quard0r.kcrbackend.dto.response;

import lombok.Builder;

@Builder
public record AuthResponse (
        String accessToken,
        String refreshToken
){
}

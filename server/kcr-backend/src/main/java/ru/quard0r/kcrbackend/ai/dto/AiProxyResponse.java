package ru.quard0r.kcrbackend.ai.dto;

import lombok.Builder;

@Builder
public record AiProxyResponse(
        int status,
        String contentType,
        String contentDisposition,
        byte[] body
) {
}

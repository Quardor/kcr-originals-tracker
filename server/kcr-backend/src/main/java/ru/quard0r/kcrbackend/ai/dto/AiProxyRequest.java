package ru.quard0r.kcrbackend.ai.dto;

import org.springframework.util.MultiValueMap;

public record AiProxyRequest(
        String method,
        String path,
        MultiValueMap<String, String> queryParams,
        String contentType,
        byte[] body,
        long callerId,
        String callerRole,
        String authorization
) {
}

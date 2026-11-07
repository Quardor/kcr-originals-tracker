package ru.quard0r.kcrbackend.clients.events.dto;

import lombok.Builder;
import ru.quard0r.kcrbackend.clients.events.CallResult;
import ru.quard0r.kcrbackend.clients.events.ClientEvent;
import ru.quard0r.kcrbackend.clients.events.ClientEventType;

import java.time.Instant;
import java.time.LocalDate;

@Builder
public record EventResponse (
        Long id,
        Long clientId,
        ClientEventType type,
        Long authorId,
        String authorUsername,
        Instant createdAt,
        String comment,
        CallResult callResult,
        String agreement,
        LocalDate clientDeadline,
        LocalDate nextContactAt,
        String recordUrl
){
    public static EventResponse from(ClientEvent event) {
        return EventResponse.builder()
                .id(event.getId())
                .clientId(event.getClient().getId())
                .type(event.getType())
                .authorId(event.getAuthor().getId())
                .authorUsername(event.getAuthor().getUsername())
                .createdAt(event.getCreatedAt())
                .comment(event.getComment())
                .callResult(event.getCallResult())
                .agreement(event.getAgreement())
                .clientDeadline(event.getClientDeadline())
                .nextContactAt(event.getNextContactAt())
                .recordUrl(event.getRecordUrl())
                .build();
    }
}

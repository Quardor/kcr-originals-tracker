package ru.quard0r.kcrbackend.clients.events.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.quard0r.kcrbackend.clients.events.CallResult;
import ru.quard0r.kcrbackend.clients.events.ClientEventType;
import ru.quard0r.kcrbackend.clients.model.WorkStage;

import java.time.LocalDate;

/**
 * Создание события истории вручную (§11.4): ручные типы — CALL/LETTER_SENT/COMMENT
 * (системные отклоняются сервисом). Поля call_result/agreement/client_deadline/
 * next_contact_at/record_url и смена stage допустимы только для CALL; stage меняется
 * по правилам этапов и создаёт отдельное событие STAGE_CHANGED.
 */
public record EventCreateRequest (
        @NotNull(message = "Event type must not be null")
        ClientEventType type,

        @Size(max = 4000, message = "Comment must be up to 4000 characters")
        String comment,

        CallResult callResult,

        @Size(max = 4000, message = "Agreement must be up to 4000 characters")
        String agreement,

        LocalDate clientDeadline,

        LocalDate nextContactAt,

        @Size(max = 1024, message = "Record url must be up to 1024 characters")
        String recordUrl,

        WorkStage stage
){
}

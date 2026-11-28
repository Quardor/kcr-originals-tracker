package ru.quard0r.kcrbackend.clients.events.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.quard0r.kcrbackend.clients.events.CallResult;
import ru.quard0r.kcrbackend.clients.events.ClientEventType;
import ru.quard0r.kcrbackend.clients.model.WorkStage;

import java.time.LocalDate;

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

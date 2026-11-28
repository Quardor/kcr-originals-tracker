package ru.quard0r.kcrbackend.clients.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.quard0r.kcrbackend.clients.model.WorkStage;

public record StageUpdateRequest (
        @NotNull(message = "Stage must not be null")
        WorkStage stage,

        @Size(max = 4000, message = "Comment must be up to 4000 characters")
        String comment
){
}

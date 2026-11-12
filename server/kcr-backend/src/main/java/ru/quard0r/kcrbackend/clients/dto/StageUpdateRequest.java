package ru.quard0r.kcrbackend.clients.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.quard0r.kcrbackend.clients.model.WorkStage;

/**
 * Смена этапа (§11.4): админ — любой этап; оператор — только рабочие этапы
 * (FIRST_CALL…COMPLETED) своих клиентов; NOT_IN_WORK/IN_POOL — за администратором.
 */
public record StageUpdateRequest (
        @NotNull(message = "Stage must not be null")
        WorkStage stage,

        @Size(max = 4000, message = "Comment must be up to 4000 characters")
        String comment
){
}

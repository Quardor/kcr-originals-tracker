package ru.quard0r.kcrbackend.clients.events.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import ru.quard0r.kcrbackend.clients.events.SanctionStatus;

/**
 * Добавление санкции по претензии. Название — свободный текст (справочник
 * доступных санкций не определён, §11.7); статус по умолчанию PLANNED.
 */
public record SanctionCreateRequest (
        @NotBlank(message = "Sanction name must not be blank")
        @Size(max = 128, message = "Sanction name must be up to 128 characters")
        String name,

        SanctionStatus status
){
}

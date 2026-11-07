package ru.quard0r.kcrbackend.clients.events.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import ru.quard0r.kcrbackend.clients.events.ClaimStatus;

import java.time.LocalDate;

/**
 * Регистрация претензии администратором (§11.4). Статус можно задать сразу
 * (например, при вводе истории); по умолчанию NEW. Номер иммутабелен.
 */
public record ClaimCreateRequest (
        @NotBlank(message = "Claim number must not be blank")
        @Size(max = 64, message = "Claim number must be up to 64 characters")
        String number,

        @NotNull(message = "Claim date must not be null")
        LocalDate claimDate,

        @Size(max = 64, message = "Incident number must be up to 64 characters")
        String incidentNumber,

        LocalDate receivedByClientAt,

        ClaimStatus status,

        @Size(max = 4000, message = "Comment must be up to 4000 characters")
        String comment
){
}

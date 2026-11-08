package ru.quard0r.kcrbackend.clients.events.dto;

import jakarta.validation.constraints.Size;
import ru.quard0r.kcrbackend.clients.events.ClaimStatus;

import java.time.LocalDate;

/**
 * Изменение претензии — patch-семантика: null = не менять. Номер и дата
 * претензии иммутабельны после создания.
 */
public record ClaimUpdateRequest (
        @Size(max = 64, message = "Incident number must be up to 64 characters")
        String incidentNumber,

        LocalDate receivedByClientAt,

        ClaimStatus status,

        @Size(max = 4000, message = "Comment must be up to 4000 characters")
        String comment
){
}

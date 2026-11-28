package ru.quard0r.kcrbackend.clients.events.dto;

import jakarta.validation.constraints.Size;
import ru.quard0r.kcrbackend.clients.events.SanctionStatus;

public record SanctionUpdateRequest (
        @Size(max = 128, message = "Sanction name must be up to 128 characters")
        String name,

        SanctionStatus status
){
}

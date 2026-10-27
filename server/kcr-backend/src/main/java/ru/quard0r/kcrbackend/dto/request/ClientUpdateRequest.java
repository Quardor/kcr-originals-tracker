package ru.quard0r.kcrbackend.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import ru.quard0r.kcrbackend.model.ClientType;

import java.util.List;

public record ClientUpdateRequest (
        @Pattern(regexp = "\\d{12}", message = "INN must be 12 digits")
        String inn,

        @Pattern(regexp = "\\d{9}", message = "KPP must be 9 digits")
        String kpp,

        @Size(max = 255, message = "Organization name must be up to 255 characters")
        String organizationName,

        @Size(max = 255, message = "Internal id must be up to 255 characters")
        String internalId,

        ClientType clientType,

        Boolean kcrActive,

        Boolean kcrBlocked,

        @Size(max = 255, message = "Tariff must be up to 255 characters")
        String tariff,

        @Size(max = 255, message = "Ekb travel time must be up to 255 characters")
        String ekbTravelTime,

        @Size(max = 255, message = "Manager must be up to 255 characters")
        String manager,

        List<String> flags,

        @Size(max = 255, message = "Contact person must be up to 255 characters")
        String contactPerson,

        @Size(max = 255, message = "Contact phone must be up to 255 characters")
        String contactPhone,

        @Email(message = "Contact email must be valid")
        @Size(max = 255, message = "Contact email must be up to 255 characters")
        String contactEmail
) {
}

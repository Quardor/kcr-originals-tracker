package ru.quard0r.kcrbackend.imports.dto;

/**
 * One client row of a preview diff: what the apply would create or update. The organization
 * name may be null — real exports omit the card columns.
 */
public record ClientImportRow(
        String kcrCode,
        String organizationName
) {
}

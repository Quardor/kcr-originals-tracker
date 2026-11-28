package ru.quard0r.kcrbackend.imports.dto;

import ru.quard0r.kcrbackend.imports.ParseIssue;

import java.util.List;

public record ClientImportPreview(
        List<ClientImportRow> toCreate,
        List<ClientImportRow> toUpdate,
        List<ParseIssue> issues
) {
}

package ru.quard0r.kcrbackend.imports.dto;

import ru.quard0r.kcrbackend.imports.ParseIssue;

import java.util.List;

public record ClientImportReport(
        int created,
        int updated,
        List<ParseIssue> issues
) {
}

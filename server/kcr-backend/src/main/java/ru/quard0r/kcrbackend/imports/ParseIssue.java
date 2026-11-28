package ru.quard0r.kcrbackend.imports;

public record ParseIssue(
        String sheet,
        Integer row,
        String column,
        String message
) {
}

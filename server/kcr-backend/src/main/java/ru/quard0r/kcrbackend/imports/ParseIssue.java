package ru.quard0r.kcrbackend.imports;

/**
 * A single problem found while parsing an import file. {@code row} is the 1-based row number
 * as shown in Excel, null when the issue relates to the whole sheet or file; {@code column}
 * is the original column header (or its Excel letter when the column has no header).
 */
public record ParseIssue(
        String sheet,
        Integer row,
        String column,
        String message
) {
}

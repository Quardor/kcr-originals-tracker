package ru.quard0r.kcrbackend.imports;

import lombok.Getter;

import java.util.List;

/**
 * Outcome of parsing an import file: valid commands (rows without errors), the issues
 * that made the remaining rows or sheets unusable, and — for the snapshot import — sheets
 * skipped because they belong to a reporting period other than the selected one. Rows with
 * at least one issue are never included into {@code commands}.
 */
@Getter
public final class ParseResult<T> {

    private final List<T> commands;
    private final List<ParseIssue> issues;
    private final List<String> skippedSheets;

    ParseResult(List<T> commands, List<ParseIssue> issues) {
        this(commands, issues, List.of());
    }

    ParseResult(List<T> commands, List<ParseIssue> issues, List<String> skippedSheets) {
        this.commands = List.copyOf(commands);
        this.issues = List.copyOf(issues);
        this.skippedSheets = List.copyOf(skippedSheets);
    }

    public boolean hasErrors() {
        return !issues.isEmpty();
    }
}

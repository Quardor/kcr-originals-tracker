package ru.quard0r.kcrbackend.imports;

import lombok.Getter;

import java.util.List;

/**
 * Outcome of parsing an import file: valid commands (rows without errors) and the issues
 * that made the remaining rows or sheets unusable. Rows with at least one issue are never
 * included into {@code commands}.
 */
@Getter
public final class ParseResult<T> {

    private final List<T> commands;
    private final List<ParseIssue> issues;

    ParseResult(List<T> commands, List<ParseIssue> issues) {
        this.commands = List.copyOf(commands);
        this.issues = List.copyOf(issues);
    }

    public boolean hasErrors() {
        return !issues.isEmpty();
    }
}

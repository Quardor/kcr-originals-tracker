package ru.quard0r.kcrbackend.imports;

import lombok.Getter;

import java.util.List;

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

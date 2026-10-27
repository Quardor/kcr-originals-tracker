package ru.quard0r.kcrbackend.imports;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import ru.quard0r.kcrbackend.imports.dto.SnapshotCreateCommand;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parser for the snapshot export workbook ("Срез &lt;дата&gt;"): each sheet is one reporting
 * period and carries the KCR code plus document counters per client, header in row 2.
 * The reporting period is taken from the sheet name; the snapshot date lives only in the
 * file name and is passed by the caller. A missing "документов всего" column yields null
 * totals — the upsert service inherits them from the previous snapshot of the period.
 */
public class SnapshotExcelParser {

    private static final Set<String> ALL_ALIASES = Arrays.stream(Field.values())
            .flatMap(field -> field.aliases.stream())
            .collect(Collectors.toUnmodifiableSet());

    public ParseResult<SnapshotCreateCommand> parse(InputStream inputStream, LocalDate snapshotDate) throws IOException {
        Objects.requireNonNull(snapshotDate, "snapshotDate must not be null");
        List<SnapshotCreateCommand> commands = new ArrayList<>();
        List<ParseIssue> issues = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            for (Sheet sheet : workbook) {
                parseSheet(sheet, snapshotDate, commands, issues);
            }
        }
        if (commands.isEmpty() && issues.isEmpty()) {
            issues.add(new ParseIssue(null, null, null, "No data rows found"));
        }
        return new ParseResult<>(commands, issues);
    }

    private void parseSheet(Sheet sheet, LocalDate snapshotDate,
                            List<SnapshotCreateCommand> commands, List<ParseIssue> issues) {
        String sheetName = sheet.getSheetName();
        int headerIndex = ExcelCells.findHeaderRow(sheet, ALL_ALIASES);
        if (headerIndex == -1) {
            if (ExcelCells.hasData(sheet)) {
                issues.add(new ParseIssue(sheetName, null, null, "Header row not found"));
            }
            return;
        }
        int codeColumn = ExcelCells.locateCodeColumn(sheet, headerIndex);
        Map<Field, ColumnBinding> columns = resolveColumns(sheet, headerIndex);
        if (codeColumn == -1 || !columns.containsKey(Field.NOT_TRANSFERRED)) {
            String missing = codeColumn == -1 ? "KCR code column not found"
                    : "Column '%s' not found".formatted(String.join("'/'", Field.NOT_TRANSFERRED.aliases));
            issues.add(new ParseIssue(sheetName, null, null, missing));
            return;
        }
        String codeTitle = columnTitle(sheet, headerIndex, codeColumn);

        List<RowCommand> rows = new ArrayList<>();
        for (int i = headerIndex + 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null) {
                parseRow(sheetName, row, codeColumn, codeTitle, columns, snapshotDate, rows, issues);
            }
        }
        excludeDuplicateCodes(sheetName, codeTitle, rows, issues);
        rows.stream().map(RowCommand::command).forEach(commands::add);
    }

    private void parseRow(String sheetName, Row row, int codeColumn, String codeTitle,
                          Map<Field, ColumnBinding> columns, LocalDate snapshotDate,
                          List<RowCommand> rows, List<ParseIssue> issues) {
        if (ExcelCells.isBlankRow(row)) {
            return;
        }
        int rowNumber = row.getRowNum() + 1;
        List<ParseIssue> rowIssues = new ArrayList<>();

        String kcrCode = null;
        String code = ExcelCells.text(row.getCell(codeColumn));
        if (code == null) {
            rowIssues.add(new ParseIssue(sheetName, rowNumber, codeTitle, "KCR code is missing"));
        } else if (!ExcelCells.matchesKcrCode(code)) {
            rowIssues.add(new ParseIssue(sheetName, rowNumber, codeTitle,
                    "Value '%s' does not look like a KCR code".formatted(code)));
        } else {
            kcrCode = code.toUpperCase(Locale.ROOT);
        }

        Integer totalDocuments = readCounter(sheetName, rowNumber, row, columns,
                Field.TOTAL_DOCUMENTS, false, rowIssues);
        Integer notTransferredDocuments = readCounter(sheetName, rowNumber, row, columns,
                Field.NOT_TRANSFERRED, true, rowIssues);
        if (totalDocuments != null && notTransferredDocuments != null
                && totalDocuments >= 0 && notTransferredDocuments >= 0
                && notTransferredDocuments > totalDocuments) {
            rowIssues.add(new ParseIssue(sheetName, rowNumber, title(columns, Field.NOT_TRANSFERRED),
                    "Not transferred documents (%d) exceed total documents (%d)"
                            .formatted(notTransferredDocuments, totalDocuments)));
        }

        if (rowIssues.isEmpty()) {
            rows.add(new RowCommand(rowNumber, new SnapshotCreateCommand(
                    kcrCode, sheetName.strip(), snapshotDate, totalDocuments, notTransferredDocuments)));
        } else {
            issues.addAll(rowIssues);
        }
    }

    private Integer readCounter(String sheetName, int rowNumber, Row row,
                                Map<Field, ColumnBinding> columns, Field field,
                                boolean required, List<ParseIssue> rowIssues) {
        String value = text(row, columns, field);
        if (value == null) {
            if (required) {
                rowIssues.add(new ParseIssue(sheetName, rowNumber, title(columns, field),
                        "Value is missing"));
            }
            return null;
        }
        Integer number = ExcelCells.toWholeNumber(value);
        if (number == null) {
            rowIssues.add(new ParseIssue(sheetName, rowNumber, title(columns, field),
                    "Value '%s' is not a whole number".formatted(value)));
        } else if (number < 0) {
            rowIssues.add(new ParseIssue(sheetName, rowNumber, title(columns, field),
                    "Value must not be negative"));
        }
        return number;
    }

    /**
     * Duplicates are an error within one sheet — one reporting period; the same code in other
     * periods (other sheets) is a legitimate client history.
     */
    private void excludeDuplicateCodes(String sheetName, String codeTitle,
                                       List<RowCommand> rows, List<ParseIssue> issues) {
        Map<String, List<RowCommand>> byCode = rows.stream()
                .collect(Collectors.groupingBy(rowCommand -> rowCommand.command().kcrCode()));
        List<RowCommand> duplicates = new ArrayList<>();
        for (List<RowCommand> group : byCode.values()) {
            if (group.size() == 1) {
                continue;
            }
            for (RowCommand rowCommand : group) {
                String otherRows = group.stream()
                        .filter(other -> other != rowCommand)
                        .map(other -> String.valueOf(other.rowNumber()))
                        .collect(Collectors.joining(", "));
                issues.add(new ParseIssue(sheetName, rowCommand.rowNumber(), codeTitle,
                        "Duplicate KCR code, also present in row(s) %s".formatted(otherRows)));
            }
            duplicates.addAll(group);
        }
        rows.removeAll(duplicates);
    }

    private Map<Field, ColumnBinding> resolveColumns(Sheet sheet, int headerIndex) {
        Row header = sheet.getRow(headerIndex);
        Map<Field, ColumnBinding> columns = new EnumMap<>(Field.class);
        for (Cell cell : header) {
            String normalized = ExcelCells.normalizeHeader(ExcelCells.text(cell));
            if (normalized == null) {
                continue;
            }
            for (Field field : Field.values()) {
                if (!columns.containsKey(field) && field.aliases.contains(normalized)) {
                    columns.put(field, new ColumnBinding(cell.getColumnIndex(), ExcelCells.text(cell)));
                    break;
                }
            }
        }
        return columns;
    }

    private String columnTitle(Sheet sheet, int headerIndex, int columnIndex) {
        Row header = sheet.getRow(headerIndex);
        String title = header == null ? null : ExcelCells.text(header.getCell(columnIndex));
        return title != null ? title : ExcelCells.columnLetter(columnIndex);
    }

    private String text(Row row, Map<Field, ColumnBinding> columns, Field field) {
        ColumnBinding binding = columns.get(field);
        return binding == null ? null : ExcelCells.text(row.getCell(binding.index()));
    }

    private String title(Map<Field, ColumnBinding> columns, Field field) {
        ColumnBinding binding = columns.get(field);
        return binding == null ? field.name() : binding.title();
    }

    private record ColumnBinding(int index, String title) {
    }

    private record RowCommand(int rowNumber, SnapshotCreateCommand command) {
    }

    private enum Field {
        KCR_CODE(ExcelCells.KCR_CODE_ALIASES),
        TOTAL_DOCUMENTS("документов всего", "всего документов", "всего документов по клиенту"),
        NOT_TRANSFERRED("всего не отправлено документов", "всего не отправлено", "не отправлено документов",
                "всего не передано документов", "не передано документов", "непередано документов", "не передано");

        final List<String> aliases;

        Field(String... aliases) {
            this.aliases = List.of(aliases);
        }

        Field(List<String> aliases) {
            this.aliases = aliases;
        }
    }
}

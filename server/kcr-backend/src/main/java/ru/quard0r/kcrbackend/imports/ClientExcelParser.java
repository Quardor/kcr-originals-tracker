package ru.quard0r.kcrbackend.imports;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import ru.quard0r.kcrbackend.imports.dto.ClientUpsertCommand;
import ru.quard0r.kcrbackend.model.ClientType;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parser for the client export workbook ("КЦР_выгрузка"): one sheet with client cards,
 * header in row 2. Columns are matched by normalized header aliases; the KCR code column
 * is located by content because in the real export its header heads an empty column while
 * the codes sit one column to the left (see {@link ExcelCells#locateCodeColumn}).
 * Embedded per-period debt columns are not imported — snapshots come from their own export.
 */
public class ClientExcelParser {

    private static final Pattern INN = Pattern.compile("^(\\d{10}|\\d{12})$");
    private static final Pattern KPP = Pattern.compile("^\\d{9}$");

    private static final Map<String, ClientType> CLIENT_TYPE_VALUES = Map.of(
            "vip", ClientType.VIP,
            "тех. партнер", ClientType.TECH_PARTNER,
            "техпартнер", ClientType.TECH_PARTNER,
            "технический партнер", ClientType.TECH_PARTNER,
            "работает через мп", ClientType.WORKS_VIA_MP,
            "работает через мпп", ClientType.WORKS_VIA_MP,
            "мпп", ClientType.WORKS_VIA_MP,
            "новичок", ClientType.NEWCOMER);

    private static final Set<String> ALL_ALIASES = Arrays.stream(Field.values())
            .flatMap(field -> field.aliases.stream())
            .collect(Collectors.toUnmodifiableSet());

    public ParseResult<ClientUpsertCommand> parse(InputStream inputStream) throws IOException {
        List<RowCommand> rows = new ArrayList<>();
        List<ParseIssue> issues = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(inputStream)) {
            for (Sheet sheet : workbook) {
                parseSheet(sheet, rows, issues);
            }
        }
        excludeDuplicateCodes(rows, issues);
        List<ClientUpsertCommand> commands = rows.stream().map(RowCommand::command).toList();
        if (commands.isEmpty() && issues.isEmpty()) {
            issues.add(new ParseIssue(null, null, null, "No data rows found"));
        }
        return new ParseResult<>(commands, issues);
    }

    private void parseSheet(Sheet sheet, List<RowCommand> rows, List<ParseIssue> issues) {
        String sheetName = sheet.getSheetName();
        int headerIndex = ExcelCells.findHeaderRow(sheet, ALL_ALIASES);
        if (headerIndex == -1) {
            if (ExcelCells.hasData(sheet)) {
                issues.add(new ParseIssue(sheetName, null, null, "Header row not found"));
            }
            return;
        }
        int codeColumn = ExcelCells.locateCodeColumn(sheet, headerIndex);
        if (codeColumn == -1) {
            issues.add(new ParseIssue(sheetName, null, null, "KCR code column not found"));
            return;
        }
        String codeTitle = columnTitle(sheet, headerIndex, codeColumn);
        Map<Field, ColumnBinding> columns = resolveColumns(sheet, headerIndex);
        for (int i = headerIndex + 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null) {
                parseRow(sheetName, row, codeColumn, codeTitle, columns, rows, issues);
            }
        }
    }

    private void parseRow(String sheetName, Row row, int codeColumn, String codeTitle,
                          Map<Field, ColumnBinding> columns, List<RowCommand> rows, List<ParseIssue> issues) {
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

        String inn = text(row, columns, Field.INN);
        if (inn != null && !INN.matcher(inn).matches()) {
            rowIssues.add(new ParseIssue(sheetName, rowNumber, title(columns, Field.INN),
                    "INN must consist of 10 or 12 digits"));
        }
        String kpp = text(row, columns, Field.KPP);
        if (kpp != null && !KPP.matcher(kpp).matches()) {
            rowIssues.add(new ParseIssue(sheetName, rowNumber, title(columns, Field.KPP),
                    "KPP must consist of 9 digits"));
        }

        ClientType clientType = null;
        String typeText = text(row, columns, Field.CLIENT_TYPE);
        if (typeText != null) {
            clientType = CLIENT_TYPE_VALUES.get(ExcelCells.normalizeHeader(typeText));
            if (clientType == null) {
                rowIssues.add(new ParseIssue(sheetName, rowNumber, title(columns, Field.CLIENT_TYPE),
                        "Unknown client type '%s'".formatted(typeText)));
            }
        }

        Boolean kcrActive = parseActive(sheetName, rowNumber, row, columns, rowIssues);
        Boolean kcrBlocked = parseBlocked(sheetName, rowNumber, row, columns, rowIssues);

        String flagsText = text(row, columns, Field.FLAGS);
        List<String> flags = flagsText == null ? null
                : Arrays.stream(flagsText.split("[,+;]"))
                        .map(String::strip)
                        .filter(value -> !value.isEmpty())
                        .toList();

        if (rowIssues.isEmpty()) {
            rows.add(new RowCommand(sheetName, rowNumber, codeTitle, ClientUpsertCommand.builder()
                    .kcrCode(kcrCode)
                    .inn(inn)
                    .kpp(kpp)
                    .organizationName(text(row, columns, Field.ORGANIZATION_NAME))
                    .internalId(text(row, columns, Field.INTERNAL_ID))
                    .clientType(clientType)
                    .kcrActive(kcrActive)
                    .kcrBlocked(kcrBlocked)
                    .tariff(text(row, columns, Field.TARIFF))
                    .ekbTravelTime(text(row, columns, Field.EKB_TRAVEL_TIME))
                    .manager(text(row, columns, Field.MANAGER))
                    .flags(flags)
                    .contactPerson(text(row, columns, Field.CONTACT_PERSON))
                    .contactPhone(text(row, columns, Field.CONTACT_PHONE))
                    .contactEmail(text(row, columns, Field.CONTACT_EMAIL))
                    .build()));
        } else {
            issues.addAll(rowIssues);
        }
    }

    private Boolean parseActive(String sheetName, int rowNumber, Row row,
                                Map<Field, ColumnBinding> columns, List<ParseIssue> rowIssues) {
        String value = text(row, columns, Field.KCR_ACTIVE);
        if (value == null) {
            return null;
        }
        String normalized = ExcelCells.normalizeHeader(value);
        // The export spells the value both "Действующий" and "Дейсвующий".
        if (normalized.startsWith("дей") || normalized.equals("да")) {
            return Boolean.TRUE;
        }
        if (normalized.startsWith("не")) {
            return Boolean.FALSE;
        }
        rowIssues.add(new ParseIssue(sheetName, rowNumber, title(columns, Field.KCR_ACTIVE),
                "Unknown KCR status '%s'".formatted(value)));
        return null;
    }

    private Boolean parseBlocked(String sheetName, int rowNumber, Row row,
                                 Map<Field, ColumnBinding> columns, List<ParseIssue> rowIssues) {
        String value = text(row, columns, Field.KCR_BLOCKED);
        if (value == null) {
            return null;
        }
        String normalized = ExcelCells.normalizeHeader(value);
        if (normalized.equals("да")) {
            return Boolean.TRUE;
        }
        if (normalized.equals("нет")) {
            return Boolean.FALSE;
        }
        rowIssues.add(new ParseIssue(sheetName, rowNumber, title(columns, Field.KCR_BLOCKED),
                "Unknown blockage value '%s', expected 'да' or 'нет'".formatted(value)));
        return null;
    }

    /**
     * Two rows with the same KCR code inside one file are an import error (ARCHITECTURE.md §11.2):
     * every row of the group is reported and excluded.
     */
    private void excludeDuplicateCodes(List<RowCommand> rows, List<ParseIssue> issues) {
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
                issues.add(new ParseIssue(rowCommand.sheet(), rowCommand.rowNumber(), rowCommand.codeTitle(),
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

    private record RowCommand(String sheet, int rowNumber, String codeTitle, ClientUpsertCommand command) {
    }

    private enum Field {
        KCR_CODE(ExcelCells.KCR_CODE_ALIASES),
        INN("инн"),
        KPP("кпп"),
        ORGANIZATION_NAME("название", "название организации", "наименование", "наименование организации",
                "наитменование организации", "название сц", "наименование сц"),
        INTERNAL_ID("внутренний id", "внутренний id / лицевой счет", "лицевой счет", "внутренний идентификатор"),
        CLIENT_TYPE("тип клиента"),
        KCR_ACTIVE("действует ли кцр"),
        KCR_BLOCKED("блокировка в сервисе"),
        TARIFF("тариф"),
        EKB_TRAVEL_TIME("время от екатеринбурга", "время в пути от екатеринбурга", "время от екб"),
        MANAGER("фио менеджера", "менеджер", "фио менеджера из кп"),
        FLAGS("спец. признаки", "спец признаки", "специальные признаки", "признаки"),
        CONTACT_PERSON("контактное лицо"),
        CONTACT_PHONE("контактный телефон", "телефон"),
        CONTACT_EMAIL("контактный email", "email", "e-mail", "почта");

        final List<String> aliases;

        Field(String... aliases) {
            this.aliases = List.of(aliases);
        }

        Field(List<String> aliases) {
            this.aliases = aliases;
        }
    }
}

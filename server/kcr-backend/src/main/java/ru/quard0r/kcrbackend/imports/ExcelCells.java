package ru.quard0r.kcrbackend.imports;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellReference;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

final class ExcelCells {

    static final List<String> KCR_CODE_ALIASES = List.of(
            "код кцр", "код kcr",
            "уникальны обезличинный идентификатор", "уникальный обезличенный идентификатор",
            "уникальный идентификатор");

    // Real exports carry codes like JXC41156; other shapes surface as row issues
    // instead of being silently accepted.
    private static final String KCR_CODE_REGEX = "^[A-Za-z]{3}\\d{5}$";

    // Cells pasted from another workbook keep the error literal as plain text.
    private static final Set<String> ERROR_ARTIFACTS = Set.of(
            "#N/A", "#REF!", "#VALUE!", "#DIV/0!", "#NAME?", "#NULL!", "#NUM!");

    private static final int HEADER_SCAN_ROWS = 10;
    private static final int CODE_COLUMN_SAMPLE_ROWS = 200;
    private static final int CODE_COLUMN_MIN_SAMPLE = 5;
    private static final double CODE_COLUMN_MATCH_RATE = 0.9;

    private ExcelCells() {
    }

    static String normalizeHeader(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT)
                .replace('ё', 'е')
                .replaceAll("\\s+", " ");
        return normalized.isEmpty() ? null : normalized;
    }

    static String text(Cell cell) {
        if (cell == null) {
            return null;
        }
        String raw = switch (cachedType(cell)) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> numericText(cell.getNumericCellValue());
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            default -> null;
        };
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.isEmpty() || ERROR_ARTIFACTS.contains(trimmed.toUpperCase(Locale.ROOT))) {
            return null;
        }
        return trimmed;
    }

    private static CellType cachedType(Cell cell) {
        CellType type = cell.getCellType();
        return type == CellType.FORMULA ? cell.getCachedFormulaResultType() : type;
    }

    private static String numericText(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) <= 1e15) {
            return String.valueOf((long) value);
        }
        return BigDecimal.valueOf(value).toPlainString();
    }

    static Integer toWholeNumber(String value) {
        if (value == null) {
            return null;
        }
        try {
            BigDecimal decimal = new BigDecimal(value);
            if (decimal.stripTrailingZeros().scale() > 0) {
                return null;
            }
            return decimal.intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }

    static boolean matchesKcrCode(String value) {
        return value != null && value.matches(KCR_CODE_REGEX);
    }

    static String columnLetter(int columnIndex) {
        return CellReference.convertNumToColString(columnIndex);
    }

    /**
     * Returns the 0-based index of the header row: the first row within the scan window
     * where at least one cell matches a known header alias, or — when nothing matched —
     * the first row with at least three filled cells. Both real export kinds keep the
     * header in row 2 with row 1 left empty.
     */
    static int findHeaderRow(Sheet sheet, Set<String> allAliases) {
        int lastRow = Math.min(sheet.getLastRowNum(), HEADER_SCAN_ROWS - 1);
        int fallback = -1;
        for (int i = 0; i <= lastRow; i++) {
            Row row = sheet.getRow(i);
            if (row == null) {
                continue;
            }
            int aliasHits = 0;
            int filled = 0;
            for (Cell cell : row) {
                String value = text(cell);
                if (value == null) {
                    continue;
                }
                filled++;
                if (allAliases.contains(normalizeHeader(value))) {
                    aliasHits++;
                }
            }
            if (aliasHits >= 1) {
                return i;
            }
            if (fallback == -1 && filled >= 3) {
                fallback = i;
            }
        }
        return fallback;
    }

    /**
     * A row is blank when none of its cells carries a value; a lone "#N/A" paste artifact
     * makes the row non-blank so that it surfaces as a row issue instead of being skipped.
     */
    static boolean isBlankRow(Row row) {
        if (row == null) {
            return true;
        }
        for (Cell cell : row) {
            switch (cachedType(cell)) {
                case BLANK -> {
                }
                case STRING -> {
                    if (!cell.getStringCellValue().isBlank()) {
                        return false;
                    }
                }
                default -> {
                    return false;
                }
            }
        }
        return true;
    }

    static boolean hasData(Sheet sheet) {
        int lastRow = Math.min(sheet.getLastRowNum(), HEADER_SCAN_ROWS + CODE_COLUMN_SAMPLE_ROWS);
        for (int i = 0; i <= lastRow; i++) {
            Row row = sheet.getRow(i);
            if (row == null) {
                continue;
            }
            for (Cell cell : row) {
                if (text(cell) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Locates the column holding KCR codes. Header aliases are trusted when the column
     * they point at actually contains code-shaped values; the client export breaks that
     * assumption ("Код КЦР" heads an empty column while the codes sit in the headerless
     * column A), so a column of code-shaped values wins over an empty aliased one.
     */
    static int locateCodeColumn(Sheet sheet, int headerRowIndex) {
        List<Integer> aliasedColumns = aliasedColumns(sheet, headerRowIndex, KCR_CODE_ALIASES);
        Map<Integer, Integer> codeMatches = new HashMap<>();
        Map<Integer, Integer> filled = new HashMap<>();
        int lastRow = Math.min(sheet.getLastRowNum(), headerRowIndex + CODE_COLUMN_SAMPLE_ROWS);
        for (int i = headerRowIndex + 1; i <= lastRow; i++) {
            Row row = sheet.getRow(i);
            if (row == null) {
                continue;
            }
            for (Cell cell : row) {
                String value = text(cell);
                if (value == null) {
                    continue;
                }
                filled.merge(cell.getColumnIndex(), 1, Integer::sum);
                if (matchesKcrCode(value)) {
                    codeMatches.merge(cell.getColumnIndex(), 1, Integer::sum);
                }
            }
        }
        int best = -1;
        for (int column : aliasedColumns) {
            if (codeMatches.getOrDefault(column, 0) > 0
                    && (best == -1 || codeMatches.get(column) > codeMatches.get(best))) {
                best = column;
            }
        }
        if (best == -1) {
            for (var entry : codeMatches.entrySet()) {
                int column = entry.getKey();
                if (aliasedColumns.contains(column)) {
                    continue;
                }
                int matches = entry.getValue();
                int total = filled.getOrDefault(column, 0);
                if (matches >= CODE_COLUMN_MIN_SAMPLE
                        && (double) matches / total >= CODE_COLUMN_MATCH_RATE
                        && (best == -1 || matches > codeMatches.get(best))) {
                    best = column;
                }
            }
        }
        return best;
    }

    static List<Integer> aliasedColumns(Sheet sheet, int headerRowIndex, List<String> aliases) {
        Row header = sheet.getRow(headerRowIndex);
        if (header == null) {
            return List.of();
        }
        return StreamSupport.stream(header.spliterator(), false)
                .filter(cell -> {
                    String normalized = normalizeHeader(text(cell));
                    return normalized != null && aliases.contains(normalized);
                })
                .map(Cell::getColumnIndex)
                .toList();
    }
}

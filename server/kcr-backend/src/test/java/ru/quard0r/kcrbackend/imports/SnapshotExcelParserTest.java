package ru.quard0r.kcrbackend.imports;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import ru.quard0r.kcrbackend.imports.dto.SnapshotCreateCommand;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotExcelParserTest {

    private final SnapshotExcelParser parser = new SnapshotExcelParser();
    private final LocalDate snapshotDate = LocalDate.of(2026, 10, 20);

    @Test
    void parsesPeriodSheets() throws IOException {
        ParseResult<SnapshotCreateCommand> result = parser.parse(
                new ByteArrayInputStream(snapshotWorkbook()), snapshotDate);

        assertTrue(result.getIssues().isEmpty(), () -> result.getIssues().toString());
        assertEquals(3, result.getCommands().size());

        SnapshotCreateCommand first = result.getCommands().get(0);
        assertEquals("BTT76330", first.kcrCode());
        assertEquals("1 кв 2026", first.reportingPeriod());
        assertEquals(snapshotDate, first.snapshotDate());
        assertEquals(96, first.totalDocuments());
        assertEquals(25, first.notTransferredDocuments());

        SnapshotCreateCommand third = result.getCommands().get(2);
        assertEquals("2 кв 2026", third.reportingPeriod());
        assertEquals("NWX51516", third.kcrCode());
        assertEquals(0, third.notTransferredDocuments());
    }

    @Test
    void reportsDuplicatesPerSheetOnly() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet first = workbook.createSheet("1 кв 2026");
            header(first);
            row(first, 2, "ISE83063", 100, 10);
            row(first, 3, "ISE83063", 100, 12);
            Sheet second = workbook.createSheet("2 кв 2026");
            header(second);
            row(second, 2, "ISE83063", 90, 5);
            workbook.write(out);

            ParseResult<SnapshotCreateCommand> result =
                    parser.parse(new ByteArrayInputStream(out.toByteArray()), snapshotDate);

            assertEquals(1, result.getCommands().size());
            assertEquals("2 кв 2026", result.getCommands().get(0).reportingPeriod());
            assertEquals(2, result.getIssues().size());
            assertEquals("1 кв 2026", result.getIssues().get(0).sheet());
            assertEquals(3, result.getIssues().get(0).row());
            assertEquals(4, result.getIssues().get(1).row());
        }
    }

    @Test
    void validatesCounters() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("1 кв 2026");
            header(sheet);
            row(sheet, 2, "BTT76330", 100, 101);
            row(sheet, 3, "ORJ23818", -5, 10);
            row(sheet, 4, "NWX51516", 12.5, 3);
            row(sheet, 5, "MJS85487", 50, null);
            row(sheet, 6, "DEP62740", 60, 4);
            workbook.write(out);

            ParseResult<SnapshotCreateCommand> result = parser.parse(new ByteArrayInputStream(out.toByteArray()), snapshotDate);

            assertEquals(1, result.getCommands().size());
            assertEquals("DEP62740", result.getCommands().get(0).kcrCode());
            assertEquals(4, result.getIssues().size());
            assertTrue(result.getIssues().get(0).message().contains("exceed total documents"));
            assertTrue(result.getIssues().get(1).message().contains("must not be negative"));
            assertTrue(result.getIssues().get(2).message().contains("not a whole number"));
            assertTrue(result.getIssues().get(3).message().contains("missing"));
        }
    }

    @Test
    void allowsMissingTotalColumn() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("1 кв 2026");
            Row header = sheet.createRow(1);
            header.createCell(0).setCellValue("Уникальны обезличинный идентификатор");
            header.createCell(4).setCellValue("Всего не отправлено документов");
            Row data = sheet.createRow(2);
            data.createCell(0).setCellValue("BTT76330");
            data.createCell(4).setCellValue(25);
            workbook.write(out);

            ParseResult<SnapshotCreateCommand> result = parser.parse(new ByteArrayInputStream(out.toByteArray()), snapshotDate);

            assertTrue(result.getIssues().isEmpty(), () -> result.getIssues().toString());
            assertEquals(1, result.getCommands().size());
            assertNull(result.getCommands().get(0).totalDocuments());
            assertEquals(25, result.getCommands().get(0).notTransferredDocuments());
        }
    }

    @Test
    void skipsSheetWithoutCounterColumn() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet broken = workbook.createSheet("1 кв 2026");
            Row brokenHeader = broken.createRow(1);
            brokenHeader.createCell(0).setCellValue("Уникальны обезличинный идентификатор");
            Row brokenData = broken.createRow(2);
            brokenData.createCell(0).setCellValue("BTT76330");

            Sheet good = workbook.createSheet("2 кв 2026");
            header(good);
            row(good, 2, "BTT76330", 69, 17);
            workbook.write(out);

            ParseResult<SnapshotCreateCommand> result = parser.parse(new ByteArrayInputStream(out.toByteArray()), snapshotDate);

            assertEquals(1, result.getCommands().size());
            assertEquals("2 кв 2026", result.getCommands().get(0).reportingPeriod());
            assertEquals(1, result.getIssues().size());
            assertEquals("1 кв 2026", result.getIssues().get(0).sheet());
            assertNull(result.getIssues().get(0).row());
            assertTrue(result.getIssues().get(0).message().contains("not found"));
        }
    }

    @Test
    void reportsMissingAndMalformedCodes() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("1 кв 2026");
            header(sheet);
            row(sheet, 2, "#N/A", 100, 10);
            row(sheet, 3, "не код", 100, 10);
            row(sheet, 4, "BTT76330", 100, 10);
            workbook.write(out);

            ParseResult<SnapshotCreateCommand> result = parser.parse(new ByteArrayInputStream(out.toByteArray()), snapshotDate);

            assertEquals(1, result.getCommands().size());
            assertEquals(2, result.getIssues().size());
            assertTrue(result.getIssues().get(0).message().contains("missing"));
            assertTrue(result.getIssues().get(1).message().contains("does not look like a KCR code"));
        }
    }

    @Test
    void requiresSnapshotDate() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("1 кв 2026");
            header(sheet);
            row(sheet, 2, "BTT76330", 100, 10);
            workbook.write(out);

            assertThrows(NullPointerException.class,
                    () -> parser.parse(new ByteArrayInputStream(out.toByteArray()), null));
        }
    }

    /**
     * Layout of the real snapshot export: header in row 2, identifier in column A
     * ("Уникальны обезличинный идентификатор"), "код СЦ"/"название СЦ" columns stay empty.
     */
    private byte[] snapshotWorkbook() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet first = workbook.createSheet("1 кв 2026");
            header(first);
            row(first, 2, "BTT76330", 96, 25);
            row(first, 3, "ORJ23818", 450, 88);
            Sheet second = workbook.createSheet("2 кв 2026");
            header(second);
            row(second, 2, "NWX51516", 78, 0);
            Sheet empty = workbook.createSheet("3 кв 2026");
            empty.createRow(0);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private void header(Sheet sheet) {
        sheet.createRow(0);
        Row header = sheet.createRow(1);
        header.createCell(0).setCellValue("Уникальны обезличинный идентификатор");
        header.createCell(1).setCellValue("код СЦ");
        header.createCell(2).setCellValue("название СЦ");
        header.createCell(3).setCellValue("документов всего");
        header.createCell(4).setCellValue("Всего не отправлено документов");
    }

    private void row(Sheet sheet, int rowIndex, String code, double total, Integer notTransferred) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(code);
        row.createCell(3).setCellValue(total);
        if (notTransferred != null) {
            row.createCell(4).setCellValue(notTransferred);
        }
    }
}

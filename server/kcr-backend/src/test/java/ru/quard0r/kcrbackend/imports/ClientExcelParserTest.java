package ru.quard0r.kcrbackend.imports;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import ru.quard0r.kcrbackend.imports.dto.ClientUpsertCommand;
import ru.quard0r.kcrbackend.model.ClientType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientExcelParserTest {

    private final ClientExcelParser parser = new ClientExcelParser();

    @Test
    void parsesRealExportLayoutWithCodesInHeaderlessColumn() throws IOException {
        ParseResult<ClientUpsertCommand> result = parser.parse(
                new ByteArrayInputStream(realExportWorkbook()));

        assertTrue(result.getIssues().isEmpty(), () -> result.getIssues().toString());
        assertEquals(6, result.getCommands().size());

        ClientUpsertCommand first = result.getCommands().get(0);
        assertEquals("JXC41156", first.kcrCode());
        assertEquals(Boolean.TRUE, first.kcrActive());
        assertEquals(Boolean.FALSE, first.kcrBlocked());
        assertEquals("Иванов И.", first.manager());
        assertNull(first.inn());
        assertNull(first.organizationName());

        ClientUpsertCommand second = result.getCommands().get(1);
        assertEquals(Boolean.FALSE, second.kcrActive());
        assertEquals(Boolean.TRUE, second.kcrBlocked());

        assertNull(result.getCommands().get(2).kcrActive());
        assertNull(result.getCommands().get(2).kcrBlocked());
        assertEquals("JFM31276", result.getCommands().get(5).kcrCode());
    }

    @Test
    void parsesTemplateLayoutWithFullCard() throws IOException {
        ParseResult<ClientUpsertCommand> result = parser.parse(
                new ByteArrayInputStream(templateWorkbook()));

        assertTrue(result.getIssues().isEmpty(), () -> result.getIssues().toString());
        assertEquals(1, result.getCommands().size());

        ClientUpsertCommand command = result.getCommands().get(0);
        assertEquals("SSO90976", command.kcrCode());
        assertEquals("7705401234", command.inn());
        assertEquals("770501001", command.kpp());
        assertEquals("ООО Ромашка", command.organizationName());
        assertEquals("л/с 42", command.internalId());
        assertEquals(ClientType.VIP, command.clientType());
        assertEquals("Базовый", command.tariff());
        assertEquals("3 часа", command.ekbTravelTime());
        assertEquals(List.of("VIP", "Интегратор"), command.flags());
        assertEquals("Петрова П.", command.contactPerson());
        assertEquals("+7 900 000-00-00", command.contactPhone());
        assertEquals("mail@example.ru", command.contactEmail());
    }

    @Test
    void uppercasesKcrCode() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Все КЦР");
            Row header = sheet.createRow(1);
            header.createCell(1).setCellValue("Код КЦР");
            Row data = sheet.createRow(2);
            data.createCell(1).setCellValue("sso90976");
            workbook.write(out);

            ParseResult<ClientUpsertCommand> result = parser.parse(new ByteArrayInputStream(out.toByteArray()));

            assertTrue(result.getIssues().isEmpty());
            assertEquals("SSO90976", result.getCommands().get(0).kcrCode());
        }
    }

    @Test
    void reportsDuplicateCodesWithRowNumbers() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Все КЦР");
            Row header = sheet.createRow(1);
            header.createCell(1).setCellValue("Код КЦР");
            row(sheet, 2, "SSO90976");
            row(sheet, 3, "JXC41156");
            row(sheet, 4, "SSO90976");
            workbook.write(out);

            ParseResult<ClientUpsertCommand> result = parser.parse(new ByteArrayInputStream(out.toByteArray()));

            assertTrue(result.getCommands().stream().noneMatch(command -> command.kcrCode().equals("SSO90976")));
            assertEquals(1, result.getCommands().size());
            assertEquals(2, result.getIssues().size());
            assertEquals(3, result.getIssues().get(0).row());
            assertEquals(5, result.getIssues().get(1).row());
            assertTrue(result.getIssues().get(0).message().contains("Duplicate KCR code"));
        }
    }

    @Test
    void reportsMissingAndMalformedCodes() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Все КЦР");
            Row header = sheet.createRow(1);
            header.createCell(1).setCellValue("Код КЦР");
            header.createCell(7).setCellValue("ИНН");
            row(sheet, 2, "#N/A");
            row(sheet, 3, "не код");
            row(sheet, 4, "JXC41156");
            workbook.write(out);

            ParseResult<ClientUpsertCommand> result = parser.parse(new ByteArrayInputStream(out.toByteArray()));

            assertEquals(1, result.getCommands().size());
            assertEquals(2, result.getIssues().size());
            assertTrue(result.getIssues().get(0).message().contains("missing"));
            assertTrue(result.getIssues().get(1).message().contains("does not look like a KCR code"));
        }
    }

    @Test
    void validatesInnAndKpp() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Все КЦР");
            Row header = sheet.createRow(1);
            header.createCell(1).setCellValue("Код КЦР");
            header.createCell(7).setCellValue("ИНН");
            header.createCell(8).setCellValue("КПП");

            Row badInn = sheet.createRow(2);
            badInn.createCell(1).setCellValue("JXC41156");
            badInn.createCell(7).setCellValue("12345");

            Row badKpp = sheet.createRow(3);
            badKpp.createCell(1).setCellValue("JFM31276");
            badKpp.createCell(8).setCellValue("7705");

            Row good = sheet.createRow(4);
            good.createCell(1).setCellValue("XBK76301");
            good.createCell(7).setCellValue(7705401234d);
            good.createCell(8).setCellValue("770501001");
            workbook.write(out);

            ParseResult<ClientUpsertCommand> result = parser.parse(new ByteArrayInputStream(out.toByteArray()));

            assertEquals(1, result.getCommands().size());
            assertEquals("7705401234", result.getCommands().get(0).inn());
            assertEquals(2, result.getIssues().size());
            assertTrue(result.getIssues().get(0).message().contains("INN"));
            assertTrue(result.getIssues().get(1).message().contains("KPP"));
        }
    }

    @Test
    void reportsUnknownClientTypeAndStatuses() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Все КЦР");
            Row header = sheet.createRow(1);
            header.createCell(1).setCellValue("Код КЦР");
            header.createCell(9).setCellValue("Действует ли КЦР");
            header.createCell(10).setCellValue("Блокировка в сервисе");
            header.createCell(15).setCellValue("Тип клиента");

            Row badStatuses = sheet.createRow(2);
            badStatuses.createCell(1).setCellValue("JXC41156");
            badStatuses.createCell(9).setCellValue("вроде бы");
            badStatuses.createCell(10).setCellValue("наверное");

            Row badType = sheet.createRow(3);
            badType.createCell(1).setCellValue("JFM31276");
            badType.createCell(9).setCellValue("Не действующий");
            badType.createCell(10).setCellValue("нет");
            badType.createCell(15).setCellValue("Непонятный");
            workbook.write(out);

            ParseResult<ClientUpsertCommand> result = parser.parse(new ByteArrayInputStream(out.toByteArray()));

            assertTrue(result.getCommands().isEmpty());
            assertEquals(3, result.getIssues().size());
            assertTrue(result.getIssues().get(0).message().contains("Unknown KCR status"));
            assertTrue(result.getIssues().get(1).message().contains("Unknown blockage value"));
            assertTrue(result.getIssues().get(2).message().contains("Unknown client type"));
        }
    }

    @Test
    void reportsMissingCodeColumnAndHeaderRow() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Все КЦР");
            Row header = sheet.createRow(1);
            header.createCell(2).setCellValue("Название");
            header.createCell(7).setCellValue("ИНН");
            header.createCell(8).setCellValue("КПП");
            Row data = sheet.createRow(2);
            data.createCell(2).setCellValue("ООО Ромашка");
            Sheet junk = workbook.createSheet("Мусор");
            junk.createRow(0).createCell(0).setCellValue("что-то");
            workbook.write(out);

            ParseResult<ClientUpsertCommand> result = parser.parse(new ByteArrayInputStream(out.toByteArray()));

            assertTrue(result.getCommands().isEmpty());
            assertEquals(2, result.getIssues().size());
            assertEquals("Все КЦР", result.getIssues().get(0).sheet());
            assertTrue(result.getIssues().get(0).message().contains("KCR code column not found"));
            assertEquals("Мусор", result.getIssues().get(1).sheet());
            assertTrue(result.getIssues().get(1).message().contains("Header row not found"));
        }
    }

    @Test
    void reportsEmptyFile() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.createSheet("Пусто");
            workbook.write(out);

            ParseResult<ClientUpsertCommand> result = parser.parse(new ByteArrayInputStream(out.toByteArray()));

            assertTrue(result.getCommands().isEmpty());
            assertEquals(1, result.getIssues().size());
            assertNull(result.getIssues().get(0).sheet());
            assertTrue(result.getIssues().get(0).message().contains("No data rows found"));
        }
    }

    /**
     * Layout of the real client export: header in row 2, "Код КЦР" heads an empty column,
     * the codes sit in the headerless column A, card columns are not filled.
     * The content-based column location needs at least five code rows to kick in.
     */
    private byte[] realExportWorkbook() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Все КЦР");
            sheet.createRow(0);
            Row header = sheet.createRow(1);
            header.createCell(1).setCellValue("Код КЦР");
            header.createCell(2).setCellValue("Название");
            header.createCell(9).setCellValue("Действует ли КЦР");
            header.createCell(10).setCellValue("Блокировка в сервисе");
            header.createCell(11).setCellValue("ФИО менеджера");

            row(sheet, 2, "JXC41156", "Дейсвующий", "нет", "Иванов И.");
            row(sheet, 3, "XBK76301", "Не действующий", "да", null);
            row(sheet, 4, "SSO90976", null, null, null);
            row(sheet, 5, "DEP62740", "Действующий", "нет", null);
            row(sheet, 6, "BTT76330", "Дейсвующий", "да", null);
            row(sheet, 7, "jfm31276", "Не действующий", "нет", null);
            sheet.createRow(8);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private byte[] templateWorkbook() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Все КЦР");
            Row header = sheet.createRow(1);
            header.createCell(1).setCellValue("Код КЦР");
            header.createCell(2).setCellValue("Название");
            header.createCell(3).setCellValue("ИНН");
            header.createCell(4).setCellValue("КПП");
            header.createCell(5).setCellValue("Внутренний ID / лицевой счет");
            header.createCell(6).setCellValue("Тип клиента");
            header.createCell(7).setCellValue("Тариф");
            header.createCell(8).setCellValue("Время от Екатеринбурга");
            header.createCell(9).setCellValue("ФИО менеджера");
            header.createCell(10).setCellValue("Спец. признаки");
            header.createCell(11).setCellValue("Контактное лицо");
            header.createCell(12).setCellValue("Контактный телефон");
            header.createCell(13).setCellValue("Email");

            Row data = sheet.createRow(2);
            data.createCell(1).setCellValue("SSO90976");
            data.createCell(2).setCellValue("ООО Ромашка");
            data.createCell(3).setCellValue("7705401234");
            data.createCell(4).setCellValue("770501001");
            data.createCell(5).setCellValue("л/с 42");
            data.createCell(6).setCellValue("VIP");
            data.createCell(7).setCellValue("Базовый");
            data.createCell(8).setCellValue("3 часа");
            data.createCell(9).setCellValue("Иванов И.");
            data.createCell(10).setCellValue("VIP, Интегратор");
            data.createCell(11).setCellValue("Петрова П.");
            data.createCell(12).setCellValue("+7 900 000-00-00");
            data.createCell(13).setCellValue("mail@example.ru");
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private void row(Sheet sheet, int rowIndex, String code, String active, String blocked, String manager) {
        Row row = sheet.createRow(rowIndex);
        row.createCell(0).setCellValue(code);
        if (active != null) {
            row.createCell(9).setCellValue(active);
        }
        if (blocked != null) {
            row.createCell(10).setCellValue(blocked);
        }
        if (manager != null) {
            row.createCell(11).setCellValue(manager);
        }
    }

    private void row(Sheet sheet, int rowIndex, String code) {
        sheet.createRow(rowIndex).createCell(1).setCellValue(code);
    }
}

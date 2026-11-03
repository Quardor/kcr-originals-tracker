package ru.quard0r.kcrbackend;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ImportApiIntegrationTest {

    private static final Pattern FIELD_PATTERN =
            Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"|\"refreshToken\"\\s*:\\s*\"([^\"]+)\"");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void clientImportFlow() throws Exception {
        String digits = String.format("%05d", System.currentTimeMillis() % 100000);
        String existingCode = "ITZ" + digits;
        String newCode = "NBW" + digits;
        String incompleteCode = "NWS" + digits;
        insertClient(existingCode, "123456789012", "Old Name");

        String operatorToken = registerAndLogin("itimp-op-" + digits, false);
        String adminToken = registerAndLogin("itimp-adm-" + digits, true);
        byte[] file = clientWorkbook(existingCode, newCode, incompleteCode);

        mockMvc.perform(multipart("/api/v1/import/clients/preview"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(multipart("/api/v1/import/clients/preview")
                        .file(new MockMultipartFile("file", file))
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(multipart("/api/v1/import/clients/preview")
                        .file(new MockMultipartFile("file", file))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(existingCode)))
                .andExpect(content().string(containsString(newCode)))
                .andExpect(content().string(containsString(incompleteCode + " cannot be created")));

        MvcResult apply = mockMvc.perform(multipart("/api/v1/import/clients")
                        .file(new MockMultipartFile("file", file))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"created\":1")))
                .andExpect(content().string(containsString("\"updated\":1")))
                .andExpect(content().string(containsString("New client " + incompleteCode + " cannot be created")))
                .andReturn();
        String applyBody = apply.getResponse().getContentAsString();
        assertFalse(applyBody.contains("New client " + existingCode));
        assertFalse(applyBody.contains("New client " + newCode));

        assertEquals("New Name", organizationName(existingCode));
        assertEquals("123456789012", inn(existingCode));
        assertEquals("Импорт М.", manager(existingCode));
        assertEquals("0987654321", inn(newCode));
        assertFalse(clientExists(incompleteCode));

        MvcResult reapply = mockMvc.perform(multipart("/api/v1/import/clients")
                        .file(new MockMultipartFile("file", file))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"created\":0")))
                .andExpect(content().string(containsString("\"updated\":2")))
                .andReturn();
        assertFalse(reapply.getResponse().getContentAsString().contains("\"created\":1"));

        mockMvc.perform(multipart("/api/v1/import/clients")
                        .file(new MockMultipartFile("file", new byte[0]))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());

        mockMvc.perform(multipart("/api/v1/import/clients")
                        .file(new MockMultipartFile("file", "not an excel file".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void snapshotImportFlow() throws Exception {
        String digits = String.format("%05d", System.currentTimeMillis() % 100000);
        String matchedCode = "SNP" + digits;
        String unmatchedCode = "UNK" + digits;
        Long clientId = insertClient(matchedCode, "1234567890", "Snapshot Org");
        insertSnapshot(clientId, "1 кв 2026", LocalDate.of(2026, 4, 10), 96, 25);

        String adminToken = registerAndLogin("itsnp-adm-" + digits, true);
        byte[] file = snapshotWorkbook(matchedCode, unmatchedCode);
        MockMultipartFile upload = new MockMultipartFile("file", file);

        mockMvc.perform(multipart("/api/v1/import/snapshots/preview")
                        .file(upload)
                        .param("snapshotDate", "2026-10-20")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(matchedCode)))
                .andExpect(content().string(containsString("\"totalInFile\":null")))
                .andExpect(content().string(containsString("\"totalResolved\":96")))
                .andExpect(content().string(containsString(unmatchedCode)));

        mockMvc.perform(multipart("/api/v1/import/snapshots")
                        .file(upload)
                        .param("snapshotDate", "2026-10-20")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"created\":1")))
                .andExpect(content().string(containsString(unmatchedCode)));

        assertEquals("1 кв 2026", jdbcTemplate.queryForObject(
                "SELECT reporting_period FROM document_transfer_snapshots "
                        + "WHERE client_id = ? AND snapshot_date = '2026-10-20'", String.class, clientId));
        assertEquals(96, jdbcTemplate.queryForObject(
                "SELECT total_documents FROM document_transfer_snapshots "
                        + "WHERE client_id = ? AND snapshot_date = '2026-10-20'", Integer.class, clientId));
        assertEquals(4, jdbcTemplate.queryForObject(
                "SELECT not_transferred_documents FROM document_transfer_snapshots "
                        + "WHERE client_id = ? AND snapshot_date = '2026-10-20'", Integer.class, clientId));

        mockMvc.perform(multipart("/api/v1/import/snapshots")
                        .file(upload)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    /**
     * Template-like layout: header in row 2, the codes sit in the "Код КЦР" column; the card
     * columns are partially filled. The first row updates an existing client (INN column is
     * empty, so the stored value must survive), the second creates a full-card client, the
     * third is a new client without INN/name and cannot be created.
     */
    private byte[] clientWorkbook(String existingCode, String newCode, String incompleteCode) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Все КЦР");
            Row header = sheet.createRow(1);
            header.createCell(1).setCellValue("Код КЦР");
            header.createCell(2).setCellValue("Название");
            header.createCell(3).setCellValue("ИНН");
            header.createCell(9).setCellValue("ФИО менеджера");

            Row update = sheet.createRow(2);
            update.createCell(1).setCellValue(existingCode);
            update.createCell(2).setCellValue("New Name");
            update.createCell(9).setCellValue("Импорт М.");

            Row create = sheet.createRow(3);
            create.createCell(1).setCellValue(newCode);
            create.createCell(2).setCellValue("New Org");
            create.createCell(3).setCellValue("0987654321");

            Row incomplete = sheet.createRow(4);
            incomplete.createCell(1).setCellValue(incompleteCode);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    /**
     * Real snapshot export layout: identifier in column A, only the "not transferred" counter —
     * the total column is absent, so it is inherited from the previous snapshot.
     */
    private byte[] snapshotWorkbook(String matchedCode, String unmatchedCode) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("1 кв 2026");
            Row header = sheet.createRow(1);
            header.createCell(0).setCellValue("Уникальны обезличинный идентификатор");
            header.createCell(4).setCellValue("Всего не отправлено документов");

            Row matched = sheet.createRow(2);
            matched.createCell(0).setCellValue(matchedCode);
            matched.createCell(4).setCellValue(4);

            Row unmatched = sheet.createRow(3);
            unmatched.createCell(0).setCellValue(unmatchedCode);
            unmatched.createCell(4).setCellValue(10);
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private Long insertClient(String kcrCode, String inn, String organizationName) {
        jdbcTemplate.update(
                "INSERT INTO clients (kcr_code, inn, organization_name) VALUES (?, ?, ?)",
                kcrCode, inn, organizationName);
        return jdbcTemplate.queryForObject("SELECT id FROM clients WHERE kcr_code = ?", Long.class, kcrCode);
    }

    private void insertSnapshot(Long clientId, String reportingPeriod, LocalDate snapshotDate,
                                Integer totalDocuments, int notTransferredDocuments) {
        jdbcTemplate.update(
                "INSERT INTO document_transfer_snapshots "
                        + "(client_id, reporting_period, snapshot_date, total_documents, not_transferred_documents) "
                        + "VALUES (?, ?, ?, ?, ?)",
                clientId, reportingPeriod, snapshotDate, totalDocuments, notTransferredDocuments);
    }

    private String registerAndLogin(String username, boolean promoteToAdmin) throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated());
        if (promoteToAdmin) {
            jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE username = ?", username);
        }
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return extractField(login.getResponse().getContentAsString(), "accessToken");
    }

    private String organizationName(String kcrCode) {
        return jdbcTemplate.queryForObject(
                "SELECT organization_name FROM clients WHERE kcr_code = ?", String.class, kcrCode);
    }

    private String inn(String kcrCode) {
        return jdbcTemplate.queryForObject(
                "SELECT inn FROM clients WHERE kcr_code = ?", String.class, kcrCode);
    }

    private String manager(String kcrCode) {
        return jdbcTemplate.queryForObject(
                "SELECT manager FROM clients WHERE kcr_code = ?", String.class, kcrCode);
    }

    private boolean clientExists(String kcrCode) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM clients WHERE kcr_code = ?", Integer.class, kcrCode);
        return count != null && count > 0;
    }

    private String extractField(String json, String field) {
        Matcher matcher = FIELD_PATTERN.matcher(json);
        while (matcher.find()) {
            if (json.startsWith("\"" + field + "\"", matcher.start())) {
                return matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            }
        }
        return "";
    }
}

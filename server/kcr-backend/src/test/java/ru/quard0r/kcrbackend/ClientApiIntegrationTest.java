package ru.quard0r.kcrbackend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ClientApiIntegrationTest {

    private static final Pattern FIELD_PATTERN =
            Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"|\"refreshToken\"\\s*:\\s*\"([^\"]+)\"");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void clientApiFlow() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String kcrCode = "kcr_it_" + suffix;
        Long clientId = insertClient(kcrCode, "IT Organization", "VIP", new String[]{"VIP"});

        String operatorToken = registerAndLogin("itop-" + suffix, false);
        String adminToken = registerAndLogin("itadm-" + suffix, true);

        mockMvc.perform(get("/api/v1/clients"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));

        mockMvc.perform(get("/api/v1/clients").param("search", suffix)
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(kcrCode)));

        mockMvc.perform(get("/api/v1/clients").param("search", suffix).param("clientType", "VIP")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(kcrCode)));

        mockMvc.perform(get("/api/v1/clients").param("search", suffix).param("clientType", "NEWCOMER")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(kcrCode))));

        mockMvc.perform(get("/api/v1/clients").param("search", suffix).param("flag", "VIP")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(kcrCode)));

        mockMvc.perform(get("/api/v1/clients").param("search", suffix).param("flag", "INTEGRATOR")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(kcrCode))));

        mockMvc.perform(get("/api/v1/clients").param("clientType", "BOGUS")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/clients").param("sortBy", "password")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/v1/clients/" + clientId)
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(kcrCode)))
                .andExpect(content().string(containsString("IT Organization")));

        mockMvc.perform(get("/api/v1/clients/999999999")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isNotFound());

        insertSnapshot(clientId, "2025 год", LocalDate.of(2026, 1, 15), 8, 2);
        insertSnapshot(clientId, "1 квартал 2026", LocalDate.of(2026, 4, 10), 10, 3);
        insertSnapshot(clientId, "1 квартал 2026", LocalDate.of(2026, 4, 20), null, 1);

        MvcResult snapshots = mockMvc.perform(get("/api/v1/clients/" + clientId + "/snapshots")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"percentNotTransferred\":25.0")))
                .andExpect(content().string(containsString("\"percentNotTransferred\":30.0")))
                .andExpect(content().string(containsString("\"percentNotTransferred\":null")))
                .andReturn();
        String snapshotsBody = snapshots.getResponse().getContentAsString();
        assertTrue(snapshotsBody.indexOf("2026-01-15") < snapshotsBody.indexOf("2026-04-10"));
        assertTrue(snapshotsBody.indexOf("2026-04-10") < snapshotsBody.indexOf("2026-04-20"));

        mockMvc.perform(get("/api/v1/clients/" + clientId + "/snapshots")
                        .param("reportingPeriod", "2025 год")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("2026-01-15")))
                .andExpect(content().string(not(containsString("2026-04-10"))));

        mockMvc.perform(patch("/api/v1/clients/" + clientId)
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationName\":\"Should not apply\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/clients/" + clientId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organizationName\":\"Updated Organization\",\"manager\":\"Ivanov\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Updated Organization")));

        assertEquals("Updated Organization", organizationName(clientId));
        assertEquals("Ivanov", manager(clientId));
        assertEquals(kcrCode, kcrCode(clientId));

        mockMvc.perform(patch("/api/v1/clients/" + clientId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inn\":\"123\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createClientFlow() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String kcrCode = "kcr_new_" + suffix;
        String operatorToken = registerAndLogin("itnew-op-" + suffix, false);
        String adminToken = registerAndLogin("itnew-adm-" + suffix, true);
        String body = "{\"kcrCode\":\"" + kcrCode + "\",\"inn\":\"123456789012\","
                + "\"organizationName\":\"Created Organization\",\"clientType\":\"VIP\"}";

        mockMvc.perform(post("/api/v1/clients")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/clients")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(content().string(containsString(kcrCode)))
                .andExpect(content().string(containsString("Created Organization")));

        mockMvc.perform(get("/api/v1/clients").param("search", suffix)
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(kcrCode)));

        mockMvc.perform(post("/api/v1/clients")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/clients")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kcrCode\":\"kcr_blank_" + suffix + "\",\"inn\":\"123456789012\","
                                + "\"organizationName\":\"\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/clients")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kcrCode\":\"kcr_inn_" + suffix + "\",\"inn\":\"123\","
                                + "\"organizationName\":\"Organization\"}"))
                .andExpect(status().isBadRequest());
    }

    private Long insertClient(String kcrCode, String organizationName, String clientType, String[] flags) {
        jdbcTemplate.update(
                "INSERT INTO clients (kcr_code, inn, organization_name, client_type, flags) VALUES (?, ?, ?, ?, ?)",
                kcrCode, "123456789012", organizationName, clientType, flags);
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

    private String organizationName(Long clientId) {
        return jdbcTemplate.queryForObject(
                "SELECT organization_name FROM clients WHERE id = ?", String.class, clientId);
    }

    private String manager(Long clientId) {
        return jdbcTemplate.queryForObject(
                "SELECT manager FROM clients WHERE id = ?", String.class, clientId);
    }

    private String kcrCode(Long clientId) {
        return jdbcTemplate.queryForObject(
                "SELECT kcr_code FROM clients WHERE id = ?", String.class, clientId);
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

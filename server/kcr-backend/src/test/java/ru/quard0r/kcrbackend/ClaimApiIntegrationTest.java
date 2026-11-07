package ru.quard0r.kcrbackend;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Претензии и санкции (§11.4, шаг 5 §11.8): мутации — админ, чтение — обе роли,
 * каждая мутация дублируется событием CLAIM/SANCTION в истории клиента.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ClaimApiIntegrationTest {

    private static final Pattern FIELD_PATTERN =
            Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"|\"refreshToken\"\\s*:\\s*\"([^\"]+)\"");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void deleteClaimClients() {
        jdbcTemplate.update("DELETE FROM sanctions WHERE claim_id IN ("
                + "SELECT c.id FROM claims c JOIN clients cl ON cl.id = c.client_id WHERE cl.kcr_code LIKE 'CLM%')");
        jdbcTemplate.update("DELETE FROM claims WHERE client_id IN ("
                + "SELECT id FROM clients WHERE kcr_code LIKE 'CLM%')");
        jdbcTemplate.update("DELETE FROM client_events WHERE client_id IN ("
                + "SELECT id FROM clients WHERE kcr_code LIKE 'CLM%')");
        jdbcTemplate.update("DELETE FROM clients WHERE kcr_code LIKE 'CLM%'");
    }

    private String clmCode(String seed) {
        return "CLM" + String.format("%05d", Math.floorMod(seed.hashCode(), 100000));
    }

    @Test
    void claimAndSanctionFlow() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String kcrCode = clmCode(suffix);
        Long clientId = insertClient(kcrCode);
        String operatorToken = registerAndLogin("clm-op-" + suffix, false);
        String adminToken = registerAndLogin("clm-adm-" + suffix, true);

        // мутации претензий — только админ
        mockMvc.perform(post("/api/v1/clients/" + clientId + "/claims")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claimBody()))
                .andExpect(status().isForbidden());

        MvcResult created = mockMvc.perform(post("/api/v1/clients/" + clientId + "/claims")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(claimBody()))
                .andExpect(status().isCreated())
                .andExpect(content().string(containsString("\"number\":\"ПР-123\"")))
                .andExpect(content().string(containsString("\"status\":\"NEW\"")))
                .andExpect(content().string(containsString("\"sanctions\":[]")))
                .andReturn();
        Long claimId = extractId(created.getResponse().getContentAsString());

        // карточка претензий читается обеими ролями, санкции вложены
        mockMvc.perform(get("/api/v1/clients/" + clientId + "/claims")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(content().string(containsString("ПР-123")));

        mockMvc.perform(get("/api/v1/clients/999999999/claims")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isNotFound());

        // статус и дата получения — patch-семантика, мутация видна в истории
        mockMvc.perform(patch("/api/v1/claims/" + claimId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SENT\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"SENT\"")));

        mockMvc.perform(patch("/api/v1/claims/" + claimId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"receivedByClientAt\":\"2026-11-05\",\"status\":\"RECEIVED\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"RECEIVED\"")))
                .andExpect(content().string(containsString("\"receivedByClientAt\":\"2026-11-05\"")));

        mockMvc.perform(patch("/api/v1/claims/999999999")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SENT\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("CLAIM_NOT_FOUND")));

        // санкция по претензии: создание и смена статуса применения
        MvcResult sanctionCreated = mockMvc.perform(post("/api/v1/claims/" + claimId + "/sanctions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Уведомление о приостановке\"}"))
                .andExpect(status().isCreated())
                .andExpect(content().string(containsString("\"status\":\"PLANNED\"")))
                .andReturn();
        Long sanctionId = extractId(sanctionCreated.getResponse().getContentAsString());

        mockMvc.perform(post("/api/v1/claims/999999999/sanctions")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Санкция\"}"))
                .andExpect(status().isNotFound());

        mockMvc.perform(patch("/api/v1/sanctions/" + sanctionId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPLIED\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"status\":\"APPLIED\"")));

        mockMvc.perform(patch("/api/v1/sanctions/999999999")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPLIED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("SANCTION_NOT_FOUND")));

        mockMvc.perform(post("/api/v1/claims/" + claimId + "/sanctions")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Ещё санкция\"}"))
                .andExpect(status().isForbidden());

        // санкция вложена в претензию клиента
        mockMvc.perform(get("/api/v1/clients/" + clientId + "/claims")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sanctions.length()").value(1))
                .andExpect(content().string(containsString("Уведомление о приостановке")));

        // каждая мутация продублирована в истории клиента
        mockMvc.perform(get("/api/v1/clients/" + clientId + "/events")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(5))
                .andExpect(jsonPath("$.content[0].type").value("SANCTION"))
                .andExpect(jsonPath("$.content[1].type").value("SANCTION"))
                .andExpect(jsonPath("$.content[2].type").value("CLAIM"))
                .andExpect(jsonPath("$.content[3].type").value("CLAIM"))
                .andExpect(jsonPath("$.content[4].type").value("CLAIM"))
                .andExpect(content().string(containsString("Претензия №ПР-123 зарегистрирована (статус NEW)")))
                .andExpect(content().string(containsString("Претензия №ПР-123: статус NEW → SENT")))
                .andExpect(content().string(containsString("Санкция «Уведомление о приостановке» по претензии №ПР-123: статус PLANNED → APPLIED")));
    }

    private String claimBody() {
        return "{\"number\":\"ПР-123\",\"claimDate\":\"2026-11-01\","
                + "\"incidentNumber\":\"INC-77\",\"comment\":\"Долг за 3 квартал\"}";
    }

    private Long extractId(String json) {
        Matcher matcher = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(json);
        if (matcher.find()) {
            return Long.parseLong(matcher.group(1));
        }
        throw new IllegalStateException("no id in response: " + json);
    }

    private Long insertClient(String kcrCode) {
        jdbcTemplate.update(
                "INSERT INTO clients (kcr_code, inn, organization_name) VALUES (?, ?, ?)",
                kcrCode, "123456789012", "Claim Organization");
        return jdbcTemplate.queryForObject("SELECT id FROM clients WHERE kcr_code = ?", Long.class, kcrCode);
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

package ru.quard0r.kcrbackend.clients;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Операторский цикл (§11.4, шаг 1 §11.8): атомарное взятие, события истории с
 * автором и временем, правила смены этапа, «сегодня нужно», переназначение.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperatorCycleIntegrationTest {

    private static final Pattern FIELD_PATTERN =
            Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"|\"refreshToken\"\\s*:\\s*\"([^\"]+)\"");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void deleteCycleClients() {
        jdbcTemplate.update("DELETE FROM sanctions WHERE claim_id IN ("
                + "SELECT c.id FROM claims c JOIN clients cl ON cl.id = c.client_id WHERE cl.kcr_code LIKE 'OPR%')");
        jdbcTemplate.update("DELETE FROM claims WHERE client_id IN ("
                + "SELECT id FROM clients WHERE kcr_code LIKE 'OPR%')");
        jdbcTemplate.update("DELETE FROM client_events WHERE client_id IN ("
                + "SELECT id FROM clients WHERE kcr_code LIKE 'OPR%')");
        jdbcTemplate.update("DELETE FROM clients WHERE kcr_code LIKE 'OPR%'");
    }

    private String oprCode(String seed) {
        return "OPR" + String.format("%05d", Math.floorMod(seed.hashCode(), 100000));
    }

    @Test
    void operatorCycleFlow() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String kcrCode = oprCode(suffix);
        Long clientId = insertClient(kcrCode);

        String operatorToken = registerAndLogin("opr-a-" + suffix, false);
        String otherOperatorToken = registerAndLogin("opr-b-" + suffix, false);
        String adminToken = registerAndLogin("opr-adm-" + suffix, true);
        Long operatorId = userId("opr-a-" + suffix);

        // взятие: оператор занимает свободного клиента
        mockMvc.perform(post("/api/v1/clients/" + clientId + "/take")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"assigneeUsername\":\"opr-a-" + suffix + "\"")))
                .andExpect(content().string(containsString("\"stage\":\"NOT_IN_WORK\"")));

        // второй оператор того же клиента не занимает (конкурентность — ниже отдельным тестом)
        mockMvc.perform(post("/api/v1/clients/" + clientId + "/take")
                        .header("Authorization", "Bearer " + otherOperatorToken))
                .andExpect(status().isConflict())
                .andExpect(content().string(containsString("CLIENT_ALREADY_TAKEN")));

        mockMvc.perform(post("/api/v1/clients/999999999/take")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isNotFound());

        // звонок: результат, договорённость, срок клиента, следующий контакт, запись, этап
        LocalDate nextContact = LocalDate.now().plusDays(2);
        mockMvc.perform(post("/api/v1/clients/" + clientId + "/events")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CALL\",\"callResult\":\"CONNECTED\","
                                + "\"comment\":\"Обсудили передачу\","
                                + "\"agreement\":\"Передаст заявления до пятницы\","
                                + "\"clientDeadline\":\"" + LocalDate.now().plusDays(5) + "\","
                                + "\"nextContactAt\":\"" + nextContact + "\","
                                + "\"recordUrl\":\"https://calls.local/rec/123\","
                                + "\"stage\":\"FIRST_CALL\"}"))
                .andExpect(status().isCreated())
                .andExpect(content().string(containsString("\"callResult\":\"CONNECTED\"")))
                .andExpect(content().string(containsString("\"authorUsername\":\"opr-a-" + suffix + "\"")));

        assertEquals(nextContact, jdbcTemplate.queryForObject(
                "SELECT next_contact_at FROM clients WHERE id = ?", LocalDate.class, clientId));
        assertEquals("FIRST_CALL", jdbcTemplate.queryForObject(
                "SELECT stage FROM clients WHERE id = ?", String.class, clientId));

        // история: новые → старые; CALL создан после события о смене этапа
        mockMvc.perform(get("/api/v1/clients/" + clientId + "/events")
                        .header("Authorization", "Bearer " + otherOperatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].type").value("CALL"))
                .andExpect(jsonPath("$.content[1].type").value("STAGE_CHANGED"))
                .andExpect(jsonPath("$.content[2].type").value("ASSIGNEE_CHANGED"))
                .andExpect(content().string(containsString("Взят в работу: opr-a-" + suffix)))
                .andExpect(content().string(containsString("Этап: NOT_IN_WORK → FIRST_CALL")));

        mockMvc.perform(get("/api/v1/clients/" + clientId + "/events")
                        .param("size", "2").param("page", "1")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].type").value("ASSIGNEE_CHANGED"));

        // контракт событий: системные типы вручную не заводятся, поля звонка — только у CALL
        mockMvc.perform(post("/api/v1/clients/" + clientId + "/events")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"STAGE_CHANGED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("EVENT_TYPE_NOT_ALLOWED")));

        mockMvc.perform(post("/api/v1/clients/" + clientId + "/events")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"CALL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("CALL_RESULT_REQUIRED")));

        mockMvc.perform(post("/api/v1/clients/" + clientId + "/events")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"COMMENT\",\"comment\":\"Ответил по почте\",\"agreement\":\"договорённость\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("EVENT_FIELD_NOT_ALLOWED")));

        mockMvc.perform(post("/api/v1/clients/" + clientId + "/events")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"LETTER_SENT\",\"comment\":\"Отправил требование\"}"))
                .andExpect(status().isCreated());

        // этапы: оператор — свои клиенты и рабочие этапы; админ — любые
        mockMvc.perform(patch("/api/v1/clients/" + clientId + "/stage")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"WAITING_CLIENT_DEADLINE\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"stage\":\"WAITING_CLIENT_DEADLINE\"")));

        mockMvc.perform(patch("/api/v1/clients/" + clientId + "/stage")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"IN_POOL\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("STAGE_CHANGE_FORBIDDEN")));

        mockMvc.perform(patch("/api/v1/clients/" + clientId + "/stage")
                        .header("Authorization", "Bearer " + otherOperatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"RECHECK\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/clients/" + clientId + "/stage")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"NOT_IN_WORK\"}"))
                .andExpect(status().isOk());

        // «сегодня нужно»: срок наступил — клиент у своего оператора и у админа
        jdbcTemplate.update("UPDATE clients SET next_contact_at = CURRENT_DATE WHERE id = ?", clientId);

        mockMvc.perform(get("/api/v1/worklist/today")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(kcrCode)));

        mockMvc.perform(get("/api/v1/worklist/today")
                        .header("Authorization", "Bearer " + otherOperatorToken))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(kcrCode))));

        mockMvc.perform(get("/api/v1/worklist/today")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(kcrCode)));

        // фильтры списка: этап и ответственный
        mockMvc.perform(get("/api/v1/clients").param("search", kcrCode.substring(3))
                        .param("stage", "NOT_IN_WORK")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(kcrCode)));

        mockMvc.perform(get("/api/v1/clients").param("search", kcrCode.substring(3))
                        .param("stage", "FIRST_CALL")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(kcrCode))));

        mockMvc.perform(get("/api/v1/clients").param("search", kcrCode.substring(3))
                        .param("assigneeId", String.valueOf(operatorId))
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(kcrCode)));

        mockMvc.perform(get("/api/v1/clients").param("search", kcrCode.substring(3))
                        .param("unassigned", "true")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(kcrCode))));

        // переназначение и освобождение — только админ
        Long otherOperatorId = userId("opr-b-" + suffix);
        mockMvc.perform(patch("/api/v1/clients/" + clientId + "/assignment")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":" + otherOperatorId + "}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(patch("/api/v1/clients/" + clientId + "/assignment")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":" + otherOperatorId + "}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"assigneeUsername\":\"opr-b-" + suffix + "\"")));

        mockMvc.perform(patch("/api/v1/clients/" + clientId + "/assignment")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":999999999}"))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("USER_NOT_FOUND")));

        mockMvc.perform(patch("/api/v1/clients/" + clientId + "/assignment")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assigneeId\":null}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"assigneeUsername\":null")));

        // справочник пользователей для переназначения — только админ
        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/users")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"username\":\"opr-a-" + suffix + "\"")))
                .andExpect(content().string(containsString("\"role\":\"OPERATOR\"")));
    }

    @Test
    void takeIsAtomicUnderConcurrentOperators() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Long clientId = insertClient(oprCode(suffix));
        String firstToken = registerAndLogin("opr-c-" + suffix, false);
        String secondToken = registerAndLogin("opr-d-" + suffix, false);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> takes = List.of(
                    () -> performTake(clientId, firstToken),
                    () -> performTake(clientId, secondToken));
            List<Future<Integer>> results = executor.invokeAll(takes);
            int ok = 0;
            int conflicts = 0;
            for (Future<Integer> result : results) {
                int status = result.get();
                if (status == 200) {
                    ok++;
                } else if (status == 409) {
                    conflicts++;
                }
            }
            assertEquals(1, ok, "ровно один оператор занимает клиента");
            assertEquals(1, conflicts, "второй получает конфликт");
        } finally {
            executor.shutdownNow();
        }

        Integer assignees = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM clients WHERE id = ? AND assignee_id IS NOT NULL", Integer.class, clientId);
        assertEquals(1, assignees);
    }

    private int performTake(Long clientId, String token) {
        try {
            MvcResult result = mockMvc.perform(post("/api/v1/clients/" + clientId + "/take")
                            .header("Authorization", "Bearer " + token))
                    .andReturn();
            return result.getResponse().getStatus();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Long insertClient(String kcrCode) {
        jdbcTemplate.update(
                "INSERT INTO clients (kcr_code, inn, organization_name) VALUES (?, ?, ?)",
                kcrCode, "123456789012", "Cycle Organization");
        return jdbcTemplate.queryForObject("SELECT id FROM clients WHERE kcr_code = ?", Long.class, kcrCode);
    }

    private Long userId(String username) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
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

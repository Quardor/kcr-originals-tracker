package ru.quard0r.kcrbackend.ai;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AiProxyIntegrationTest {

    private static final String XLSX_MEDIA_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final Pattern ACCESS_TOKEN_PATTERN =
            Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"");
    private static final AtomicReference<Function<RecordedRequest, MockResponse>> mlBehaviour =
            new AtomicReference<>();
    private static final MockWebServer ml = new MockWebServer();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    static {
        ml.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return mlBehaviour.get().apply(request);
            }

            @Override
            public MockResponse peek() {
                return mlBehaviour.get().apply(null);
            }
        });
    }

    @DynamicPropertySource
    static void mlProperties(DynamicPropertyRegistry registry) throws IOException {
        ml.start();
        registry.add("app.ml.base-url", () -> ml.url("/").toString());
        registry.add("app.ml.connect-timeout-ms", () -> "2000");
        registry.add("app.ml.read-timeout-ms", () -> "5000");
    }

    @AfterAll
    static void stopMl() throws Exception {
        drainMlRequests();
        ml.shutdown();
    }

    @BeforeEach
    void answerWithServerError() throws InterruptedException {
        drainMlRequests();
        mlBehaviour.set(request -> json(500, "{\"code\":\"INTERNAL_ERROR\"}"));
    }

    @AfterEach
    void deleteTestUsers() {
        AiTestUsers.delete(jdbcTemplate);
    }

    private static void drainMlRequests() throws InterruptedException {
        while (ml.takeRequest(100, TimeUnit.MILLISECONDS) != null) {
        }
    }

    @Test
    void anonymousRequestsNeverReachMl() throws Exception {
        int requestsBefore = ml.getRequestCount();

        mockMvc.perform(get("/api/v1/ai/analytics/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("AUTHENTICATION_REQUIRED")));

        mockMvc.perform(post("/api/v1/ai/chat/ticket"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/ai/prioritize")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"кто в приоритете\"}"))
                .andExpect(status().isUnauthorized());

        assertEquals(requestsBefore, ml.getRequestCount());
    }

    @Test
    void operatorGetIsForwardedWithServiceHeaders() throws Exception {
        String username = "ai-op-" + suffix();
        String operatorToken = registerAndLogin(username, false);
        mlBehaviour.set(request -> json(200, "{\"total\":3}"));

        mockMvc.perform(get("/api/v1/ai/analytics/top-debt?limit=25")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"total\":3}"));

        RecordedRequest recorded = ml.takeRequest();
        assertEquals("GET", recorded.getMethod());
        assertEquals("/api/v1/ai/analytics/top-debt?limit=25", recorded.getPath());
        assertEquals(internalToken(), recorded.getHeader("X-Internal-Token"));
        assertEquals(String.valueOf(userId(username)), recorded.getHeader("X-Kcr-User-Id"));
        assertEquals("OPERATOR", recorded.getHeader("X-Kcr-User-Role"));
        assertEquals("Bearer " + operatorToken, recorded.getHeader("Authorization"));
    }

    @Test
    void adminPostIsForwardedWithItsJsonBody() throws Exception {
        String username = "ai-adm-" + suffix();
        String adminToken = registerAndLogin(username, true);
        mlBehaviour.set(request -> json(200, "{\"items\":[]}"));
        String body = "{\"prompt\":\"не VIP, не передано больше 25%\",\"limit\":5}";

        mockMvc.perform(post("/api/v1/ai/prioritize")
                        .header("Authorization", "Bearer " + adminToken)
                        .characterEncoding(StandardCharsets.UTF_8.name())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"items\":[]}"));

        RecordedRequest recorded = ml.takeRequest();
        assertEquals("POST", recorded.getMethod());
        assertEquals("/api/v1/ai/prioritize", recorded.getPath());
        assertEquals(body, recorded.getBody().readString(StandardCharsets.UTF_8));
        assertTrue(recorded.getHeader("Content-Type").startsWith("application/json"));
        assertEquals("ADMIN", recorded.getHeader("X-Kcr-User-Role"));
        assertEquals(String.valueOf(userId(username)), recorded.getHeader("X-Kcr-User-Id"));
    }

    @Test
    void xlsxExportKeepsBinaryBodyAndHeaders() throws Exception {
        String adminToken = registerAndLogin("ai-xls-" + suffix(), true);
        byte[] workbook = new byte[]{'P', 'K', 3, 4, 0, (byte) 0xFF, (byte) 0xFE, 'x', 'l', 's'};
        String disposition = "attachment; filename=\"report.xlsx\"";
        mlBehaviour.set(request -> new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", XLSX_MEDIA_TYPE)
                .setHeader("Content-Disposition", disposition)
                .setHeader("Connection", "close")
                .setBody(new Buffer().write(workbook)));

        MvcResult result = mockMvc.perform(post("/api/v1/ai/export/xlsx")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"analytics_summary\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", disposition))
                .andExpect(content().contentType(XLSX_MEDIA_TYPE))
                .andReturn();

        assertArrayEquals(workbook, result.getResponse().getContentAsByteArray());
    }

    @Test
    void operatorIsRefusedByBackendOnAdminRoutes() throws Exception {
        String operatorToken = registerAndLogin("ai-ops-" + suffix(), false);
        int requestsBefore = ml.getRequestCount();

        mockMvc.perform(post("/api/v1/ai/prioritize")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"кто в приоритете\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/ai/export/xlsx")
                        .header("Authorization", "Bearer " + operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"analytics_summary\"}"))
                .andExpect(status().isForbidden());

        assertEquals(requestsBefore, ml.getRequestCount());
    }

    @Test
    void mlErrorsKeepTheirStatusAndContract() throws Exception {
        String adminToken = registerAndLogin("ai-err-" + suffix(), true);
        mlBehaviour.set(request -> json(400,
                "{\"code\":\"VALIDATION_FAILED\",\"message\":\"prompt: String should have at least 1 character\"}"));

        mockMvc.perform(post("/api/v1/ai/prioritize")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("\"code\":\"VALIDATION_FAILED\"")));

        mlBehaviour.set(request -> json(504,
                "{\"code\":\"LLM_TIMEOUT\",\"message\":\"LLM provider did not respond in time\"}"));

        mockMvc.perform(post("/api/v1/ai/prioritize")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"кто в приоритете\"}"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(content().string(containsString("\"code\":\"LLM_TIMEOUT\"")));
    }

    @Test
    void userRejectionPassesThroughButServiceRejectionIsAnOutage() throws Exception {
        String operatorToken = registerAndLogin("ai-rej-" + suffix(), false);

        mlBehaviour.set(request -> json(401,
                "{\"code\":\"AUTHENTICATION_REQUIRED\",\"message\":\"kcr-backend rejected the token\"}"));

        mockMvc.perform(get("/api/v1/ai/analytics/summary")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("\"code\":\"AUTHENTICATION_REQUIRED\"")));

        mlBehaviour.set(request -> json(401,
                "{\"code\":\"INTERNAL_TOKEN_REJECTED\",\"message\":\"Internal service authentication failed\"}"));

        mockMvc.perform(get("/api/v1/ai/analytics/summary")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(containsString("\"code\":\"AI_SERVICE_UNAVAILABLE\"")));
    }

    @Test
    void unreachableMlBecomesServiceUnavailable() throws Exception {
        String operatorToken = registerAndLogin("ai-out-" + suffix(), false);
        mlBehaviour.set(request -> new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));

        mockMvc.perform(get("/api/v1/ai/clients/42/brief")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(containsString("\"code\":\"AI_SERVICE_UNAVAILABLE\"")));
    }

    @Test
    void unknownAiRouteIsNotFound() throws Exception {
        String operatorToken = registerAndLogin("ai-404-" + suffix(), false);
        int requestsBefore = ml.getRequestCount();

        mockMvc.perform(get("/api/v1/ai/unknown")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("\"code\":\"RESOURCE_NOT_FOUND\"")));

        assertEquals(requestsBefore, ml.getRequestCount());
    }

    @Test
    void chatTicketIsIssuedToAnyAuthenticatedUser() throws Exception {
        String operatorToken = registerAndLogin("ai-tkt-" + suffix(), false);

        mockMvc.perform(post("/api/v1/ai/chat/ticket")
                        .header("Authorization", "Bearer " + operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresInSeconds").value(30))
                .andExpect(jsonPath("$.ticket").value(matchesPattern("[A-Za-z0-9_-]{43}")));
    }

    private static MockResponse json(int status, String body) {
        return new MockResponse()
                .setResponseCode(status)
                .setHeader("Content-Type", "application/json")
                .setHeader("Connection", "close")
                .setBody(body);
    }

    private static String internalToken() {
        return System.getProperty("ML_INTERNAL_TOKEN");
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private long userId(String username) {
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
        Matcher matcher = ACCESS_TOKEN_PATTERN.matcher(login.getResponse().getContentAsString());
        assertTrue(matcher.find());
        return matcher.group(1);
    }
}

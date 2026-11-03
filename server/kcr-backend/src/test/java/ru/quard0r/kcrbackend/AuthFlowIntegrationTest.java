package ru.quard0r.kcrbackend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowIntegrationTest {

    private static final Pattern FIELD_PATTERN = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"|\"refreshToken\"\\s*:\\s*\"([^\"]+)\"");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${app.security.auth-rate-limit.capacity}")
    private int rateLimitCapacity;

    @Value("${app.security.account-lockout.max-failed-attempts}")
    private int maxFailedAttempts;

    @Test
    void fullAuthFlow() throws Exception {
        String username = "ituser-" + UUID.randomUUID();
        String password = "password123";

        MvcResult register = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String registerBody = register.getResponse().getContentAsString();
        String accessToken = extractField(registerBody, "accessToken");
        String refreshToken = extractField(registerBody, "refreshToken");
        assertNotEquals("", accessToken);
        assertNotEquals("", refreshToken);
        assertEquals(0, countByTokenHash(refreshToken));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/persons").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/persons"))
                .andExpect(status().isUnauthorized());

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String newRefreshToken = extractField(refreshed.getResponse().getContentAsString(), "refreshToken");
        assertNotEquals(refreshToken, newRefreshToken);
        assertEquals(0, countByTokenHash(refreshToken));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());

        // The family is revoked: the freshly issued token dies together with the reused one.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + newRefreshToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentRefreshWithSameTokenRotatesItExactlyOnce() throws Exception {
        String username = "ituser-" + UUID.randomUUID();
        String password = "password123";

        MvcResult register = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String refreshToken = extractField(register.getResponse().getContentAsString(), "refreshToken");

        // With FOR UPDATE on the token row the outcome is the same under any interleaving:
        // the first transaction commits the rotation, the rest see the rotated token and trip
        // reuse detection, which revokes the family together with the winner's fresh token.
        int threads = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<RefreshResponse>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    var response = mockMvc.perform(post("/api/v1/auth/refresh")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                            .andReturn()
                            .getResponse();
                    return new RefreshResponse(response.getStatus(), response.getContentAsString());
                }));
            }
            start.countDown();

            String winnerToken = null;
            int okCount = 0;
            for (Future<RefreshResponse> result : results) {
                RefreshResponse response = result.get(30, TimeUnit.SECONDS);
                if (response.status() == 200) {
                    okCount++;
                    winnerToken = extractField(response.body(), "refreshToken");
                } else {
                    assertEquals(401, response.status());
                }
            }
            assertEquals(1, okCount);
            assertNotEquals("", winnerToken);

            // The losers' reuse detection revoked the whole family, winner's fresh token included.
            mockMvc.perform(post("/api/v1/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"" + winnerToken + "\"}"))
                    .andExpect(status().isUnauthorized());
        } finally {
            executor.shutdownNow();
        }

        // The user recovers by logging in again.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void logoutRevokesAllRefreshTokensAndRequiresAuthentication() throws Exception {
        String username = "ituser-" + UUID.randomUUID();
        String password = "password123";

        MvcResult register = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String accessToken = extractField(register.getResponse().getContentAsString(), "accessToken");
        String registerRefreshToken = extractField(register.getResponse().getContentAsString(), "refreshToken");

        // A second session of the same user: logout must kill both token families.
        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String loginRefreshToken = extractField(login.getResponse().getContentAsString(), "refreshToken");

        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + registerRefreshToken + "\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + loginRefreshToken + "\"}"))
                .andExpect(status().isUnauthorized());

        // The user recovers by logging in again.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void accountLockoutBlocksLoginUntilLockExpires() throws Exception {
        String username = "ituser-" + UUID.randomUUID();
        String password = "password123";

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isCreated());

        for (int i = 0; i < maxFailedAttempts; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"wrong-password\"}"))
                    .andExpect(status().isUnauthorized());
        }

        // The correct password is rejected too: the account is locked, the response stays
        // the same single 401 as for wrong credentials (no reason is disclosed).
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isUnauthorized());

        // Expire the lock directly instead of waiting out the configured duration.
        jdbcTemplate.update(
                "UPDATE users SET locked_until = now() - interval '1 second' WHERE username = ?", username);

        MvcResult recovered = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertNotEquals("", extractField(recovered.getResponse().getContentAsString(), "accessToken"));

        Integer failedAttempts = jdbcTemplate.queryForObject(
                "SELECT failed_attempts FROM users WHERE username = ?", Integer.class, username);
        assertEquals(0, failedAttempts);
    }

    @Test
    void loginRateLimitReturns429WhenBucketIsExhausted() throws Exception {
        String username = "ituser-" + UUID.randomUUID();
        String password = "password123";

        // The register request consumes the first token of the username+IP bucket.
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isCreated());

        int allowedLogins = rateLimitCapacity - 1;
        for (int i = 0; i < allowedLogins; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"wrong-password\"}"))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void accessTokenOfDeletedUserIsRejectedWith401() throws Exception {
        // Подпись верна, но пользователя больше нет: конвертер бросает AuthenticationException,
        // 401 в едином контракте — а не 500 из-за исключения внутри фильтрации.
        String username = "ituser-" + UUID.randomUUID();
        MvcResult register = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String accessToken = extractField(register.getResponse().getContentAsString(), "accessToken");

        jdbcTemplate.update(
                "DELETE FROM refresh_tokens WHERE user_id = (SELECT id FROM users WHERE username = ?)",
                username);
        jdbcTemplate.update("DELETE FROM users WHERE username = ?", username);

        mockMvc.perform(get("/api/persons").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("\"code\":\"AUTHENTICATION_REQUIRED\"")));
    }

    @Test
    void registerNormalizesEmailBeforeValidationAndUniquenessCheck() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "itmail-" + suffix + "@mail.com";

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"itmail-a-" + suffix + "\",\"password\":\"password123\","
                                + "\"email\":\" " + email + " \"}"))
                .andExpect(status().isCreated());

        assertEquals(email, jdbcTemplate.queryForObject(
                "SELECT email FROM users WHERE username = ?", String.class, "itmail-a-" + suffix));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"itmail-b-" + suffix + "\",\"password\":\"password123\","
                                + "\"email\":\"" + email + "\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"itmail-c-" + suffix + "\",\"password\":\"password123\","
                                + "\"email\":\"   \"}"))
                .andExpect(status().isCreated());

        assertNull(jdbcTemplate.queryForObject(
                "SELECT email FROM users WHERE username = ?", String.class, "itmail-c-" + suffix));
    }

    private record RefreshResponse(int status, String body) {
    }

    private int countByTokenHash(String tokenHash) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_tokens WHERE token_hash = ?",
                Integer.class,
                tokenHash);
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

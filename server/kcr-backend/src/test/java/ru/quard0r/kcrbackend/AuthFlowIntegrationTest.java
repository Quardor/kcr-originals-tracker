package ru.quard0r.kcrbackend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthFlowIntegrationTest {

    private static final Pattern FIELD_PATTERN = Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"|\"refreshToken\"\\s*:\\s*\"([^\"]+)\"");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullAuthFlow() throws Exception {
        String username = "ituser-" + UUID.randomUUID();
        String password = "password123";

        MvcResult register = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String registerBody = register.getResponse().getContentAsString();
        String accessToken = extractField(registerBody, "accessToken");
        String refreshToken = extractField(registerBody, "refreshToken");
        assertNotEquals("", accessToken);
        assertNotEquals("", refreshToken);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/persons").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/persons"))
                .andExpect(status().isUnauthorized());

        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String newRefreshToken = extractField(refreshed.getResponse().getContentAsString(), "refreshToken");
        assertNotEquals(refreshToken, newRefreshToken);

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());
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

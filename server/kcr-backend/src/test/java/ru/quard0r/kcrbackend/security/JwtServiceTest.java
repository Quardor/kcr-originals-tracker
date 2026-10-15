package ru.quard0r.kcrbackend.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;
import ru.quard0r.kcrbackend.model.Role;
import ru.quard0r.kcrbackend.model.User;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {

    private static final String SECRET = "test-secret-key-with-at-least-32-bytes!!";

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        jwtService = new JwtService(key);
        ReflectionTestUtils.setField(jwtService, "accessTokenExpiration", 60_000L);
    }

    private UserDetails userDetails(String username) {
        return org.springframework.security.core.userdetails.User.builder()
                .username(username)
                .password("$2a$10$hash")
                .authorities("ROLE_OPERATOR")
                .build();
    }

    @Test
    void tokenRoundTripKeepsUsername() {
        User user = User.builder()
                .username("user")
                .password("hash")
                .email("user@mail.com")
                .role(Role.OPERATOR)
                .build();

        String token = jwtService.generateAccessToken(user);

        assertEquals("user", jwtService.extractUsername(token));
        assertTrue(jwtService.isTokenValid(token, userDetails("user")));
        assertFalse(jwtService.isTokenValid(token, userDetails("other")));
    }

    @Test
    void expiredTokenFailsToParse() {
        ReflectionTestUtils.setField(jwtService, "accessTokenExpiration", -1_000L);
        User user = User.builder().username("user").password("hash").role(Role.OPERATOR).build();

        String token = jwtService.generateAccessToken(user);

        assertThrows(ExpiredJwtException.class, () -> jwtService.extractUsername(token));
    }

    @Test
    void tamperedTokenFailsToParse() {
        User user = User.builder().username("user").password("hash").role(Role.OPERATOR).build();
        String token = jwtService.generateAccessToken(user) + "x";

        assertThrows(JwtException.class, () -> jwtService.extractUsername(token));
    }
}

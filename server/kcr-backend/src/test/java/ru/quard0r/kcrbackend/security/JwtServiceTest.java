package ru.quard0r.kcrbackend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.quard0r.kcrbackend.model.Role;
import ru.quard0r.kcrbackend.model.User;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

// JwtService отвечает только за выдачу; проверка токена — NimbusJwtDecoder
// (SecurityConfig), пути валидного/истёкшего/подделанного токена покрыты
// конвертером и интеграционными сценариями.
class JwtServiceTest {

    private static final String SECRET = "test-secret-key-with-at-least-32-bytes!!";

    private JwtService jwtService;
    private SecretKey key;

    @BeforeEach
    void setUp() {
        key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        jwtService = new JwtService(key);
        ReflectionTestUtils.setField(jwtService, "accessTokenExpiration", 60_000L);
    }

    private User user(long id, String username) {
        return User.builder()
                .id(id)
                .username(username)
                .password("hash")
                .email("user@mail.com")
                .role(Role.OPERATOR)
                .build();
    }

    private Claims parse(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    @Test
    void tokenCarriesUserIdAndClaims() {
        User user = user(42L, "user");

        String token = jwtService.generateAccessToken(user);

        Claims claims = parse(token);
        assertEquals("42", claims.getSubject());
        assertEquals("user", claims.get("preferred_username", String.class));
        assertEquals("user@mail.com", claims.get("email", String.class));
        assertEquals("OPERATOR", claims.get("roles", String.class));
        assertNotNull(claims.getId());
        assertNotNull(claims.getExpiration());
    }

    @Test
    void eachTokenGetsUniqueJti() {
        User user = user(42L, "user");

        String first = jwtService.generateAccessToken(user);
        String second = jwtService.generateAccessToken(user);

        assertNotEquals(parse(first).getId(), parse(second).getId());
    }
}

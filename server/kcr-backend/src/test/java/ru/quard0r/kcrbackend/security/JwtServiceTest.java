package ru.quard0r.kcrbackend.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.quard0r.kcrbackend.model.Role;
import ru.quard0r.kcrbackend.model.User;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void tokenRoundTripKeepsUserIdAndClaims() {
        User user = user(42L, "user");

        String token = jwtService.generateAccessToken(user);

        assertEquals(42L, jwtService.extractUserId(token));
        assertTrue(jwtService.isTokenValid(token, new CustomUserDetails(user)));
        assertFalse(jwtService.isTokenValid(token, new CustomUserDetails(user(43L, "user"))));

        Claims claims = parse(token);
        assertEquals("42", claims.getSubject());
        assertEquals("user", claims.get("preferred_username", String.class));
        assertEquals("user@mail.com", claims.get("email", String.class));
        assertEquals("OPERATOR", claims.get("roles", String.class));
        assertNotNull(claims.getId());
    }

    @Test
    void eachTokenGetsUniqueJti() {
        User user = user(42L, "user");

        String first = jwtService.generateAccessToken(user);
        String second = jwtService.generateAccessToken(user);

        assertNotEquals(parse(first).getId(), parse(second).getId());
    }

    @Test
    void legacyUsernameSubjectFailsToParseAsUserId() {
        // До смены claims (п. 13 §10) subject был username: после выката такие токены
        // невалидны — NumberFormatException ловится фильтром, ответ 401.
        String legacyToken = Jwts.builder()
                .subject("user")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000L))
                .signWith(key)
                .compact();

        assertThrows(NumberFormatException.class, () -> jwtService.extractUserId(legacyToken));
    }

    @Test
    void expiredTokenFailsToParse() {
        ReflectionTestUtils.setField(jwtService, "accessTokenExpiration", -1_000L);
        User user = user(42L, "user");

        String token = jwtService.generateAccessToken(user);

        assertThrows(ExpiredJwtException.class, () -> jwtService.extractUserId(token));
    }

    @Test
    void tamperedTokenFailsToParse() {
        User user = user(42L, "user");
        String token = jwtService.generateAccessToken(user) + "x";

        assertThrows(JwtException.class, () -> jwtService.extractUserId(token));
    }
}

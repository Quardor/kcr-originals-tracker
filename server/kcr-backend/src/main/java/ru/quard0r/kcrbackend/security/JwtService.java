package ru.quard0r.kcrbackend.security;

import io.jsonwebtoken.Jwts;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.quard0r.kcrbackend.users.model.User;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.UUID;

/**
 * Выдача access-токенов (jjwt). Проверка — на стороне ресурсного сервера:
 * SecurityConfig собирает NimbusJwtDecoder из того же ключа.
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    @Value("${jwt.access-expiration}")
    private long accessTokenExpiration;

    private final SecretKey jwtSecretKey;

    public String generateAccessToken(User user) {
        return Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .id(UUID.randomUUID().toString())
                .claim("preferred_username", user.getUsername())
                .claim("email", user.getEmail())
                .claim("roles", user.getRole())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpiration))
                .signWith(jwtSecretKey, Jwts.SIG.HS256)
                .compact();
    }
}

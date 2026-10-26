package ru.quard0r.kcrbackend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.quard0r.kcrbackend.exception.base.ServerException;
import ru.quard0r.kcrbackend.model.RefreshToken;
import ru.quard0r.kcrbackend.model.User;
import ru.quard0r.kcrbackend.repository.RefreshTokenRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    @Value("${jwt.refresh-expiration}")
    private long refreshTokenExpiration;

    private final RefreshTokenRepository refreshTokenRepository;

    public record RotationResult(User user, String refreshToken) {
    }

    @Transactional
    public String createRefreshToken(User user) {
        return issueRefreshToken(user);
    }

    @Transactional
    public Optional<RotationResult> rotate(String rawRefreshToken) {
        Optional<RefreshToken> found = refreshTokenRepository
                .findByTokenHash(sha256Hex(rawRefreshToken));
        if (found.isEmpty()) {
            return Optional.empty();
        }

        RefreshToken refreshToken = found.get();
        if (refreshToken.getRotatedDate() != null) {
            refreshTokenRepository.deleteByUser(refreshToken.getUser());
            return Optional.empty();
        }
        if (refreshToken.getExpiryDate().isBefore(Instant.now())) {
            refreshTokenRepository.delete(refreshToken);
            return Optional.empty();
        }

        refreshToken.setRotatedDate(Instant.now());
        String newRawToken = issueRefreshToken(refreshToken.getUser());
        return Optional.of(new RotationResult(refreshToken.getUser(), newRawToken));
    }

    private String issueRefreshToken(User user) {
        String rawToken = UUID.randomUUID().toString();
        RefreshToken refreshToken = RefreshToken.builder()
                .tokenHash(sha256Hex(rawToken))
                .user(user)
                .expiryDate(Instant.now().plusMillis(refreshTokenExpiration))
                .build();
        refreshTokenRepository.save(refreshToken);
        return rawToken;
    }

    private static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new ServerException("SHA-256 digest is not available in the JRE");
        }
    }
}

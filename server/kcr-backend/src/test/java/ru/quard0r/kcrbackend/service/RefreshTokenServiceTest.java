package ru.quard0r.kcrbackend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import ru.quard0r.kcrbackend.model.RefreshToken;
import ru.quard0r.kcrbackend.model.Role;
import ru.quard0r.kcrbackend.model.User;
import ru.quard0r.kcrbackend.repository.RefreshTokenRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

    private static final long REFRESH_TOKEN_EXPIRATION = 604_800_000L;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @InjectMocks
    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(refreshTokenService, "refreshTokenExpiration", REFRESH_TOKEN_EXPIRATION);
    }

    private User user() {
        return User.builder()
                .username("user")
                .password("hash")
                .email("user@mail.com")
                .role(Role.OPERATOR)
                .build();
    }

    private RefreshToken token(User user, Instant expiryDate, Instant rotatedDate) {
        return RefreshToken.builder()
                .tokenHash("stored-hash")
                .user(user)
                .expiryDate(expiryDate)
                .rotatedDate(rotatedDate)
                .build();
    }

    @Test
    void createRefreshTokenStoresSha256HashInsteadOfRawToken() {
        User user = user();
        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        String rawToken = refreshTokenService.createRefreshToken(user);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        assertEquals(sha256Hex(rawToken), captor.getValue().getTokenHash());
        assertNull(captor.getValue().getRotatedDate());
        assertEquals(user, captor.getValue().getUser());
    }

    @Test
    void rotateMarksOldTokenRotatedAndIssuesNewOne() {
        User user = user();
        RefreshToken oldToken = token(user, Instant.now().plusSeconds(600), null);
        when(refreshTokenRepository.findByTokenHash(sha256Hex("raw-token")))
                .thenReturn(Optional.of(oldToken));
        when(refreshTokenRepository.save(any(RefreshToken.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Optional<RefreshTokenService.RotationResult> result = refreshTokenService.rotate("raw-token");

        assertTrue(result.isPresent());
        assertEquals(user, result.get().user());
        assertNotEquals("raw-token", result.get().refreshToken());
        assertNotNull(oldToken.getRotatedDate());

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        assertEquals(sha256Hex(result.get().refreshToken()), captor.getValue().getTokenHash());
        assertNull(captor.getValue().getRotatedDate());
    }

    @Test
    void rotateRejectsUnknownTokenWithoutSideEffects() {
        when(refreshTokenRepository.findByTokenHash(sha256Hex("unknown-token")))
                .thenReturn(Optional.empty());

        assertTrue(refreshTokenService.rotate("unknown-token").isEmpty());

        verify(refreshTokenRepository, never()).deleteByUser(any());
        verify(refreshTokenRepository, never()).delete(any(RefreshToken.class));
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    void rotateDeletesExpiredTokenAndRejectsIt() {
        User user = user();
        RefreshToken expired = token(user, Instant.now().minusSeconds(1), null);
        when(refreshTokenRepository.findByTokenHash(sha256Hex("expired-token")))
                .thenReturn(Optional.of(expired));

        assertTrue(refreshTokenService.rotate("expired-token").isEmpty());

        verify(refreshTokenRepository).delete(expired);
        verify(refreshTokenRepository, never()).deleteByUser(any());
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    @Test
    void rotateRevokesWholeTokenFamilyOnReuseOfRotatedToken() {
        User user = user();
        RefreshToken rotated = token(user, Instant.now().plusSeconds(600), Instant.now().minusSeconds(1));
        when(refreshTokenRepository.findByTokenHash(sha256Hex("reused-token")))
                .thenReturn(Optional.of(rotated));

        assertTrue(refreshTokenService.rotate("reused-token").isEmpty());

        verify(refreshTokenRepository).deleteByUser(user);
        verify(refreshTokenRepository, never()).delete(rotated);
        verify(refreshTokenRepository, never()).save(any(RefreshToken.class));
    }

    private static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}

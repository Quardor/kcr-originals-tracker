package ru.quard0r.kcrbackend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.quard0r.kcrbackend.exception.InvalidCredentialsException;
import ru.quard0r.kcrbackend.exception.UserNotFoundException;
import ru.quard0r.kcrbackend.model.RefreshToken;
import ru.quard0r.kcrbackend.model.User;
import ru.quard0r.kcrbackend.repository.RefreshTokenRepository;
import ru.quard0r.kcrbackend.repository.UserRepository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String USER_NOT_FOUND_MESSAGE = "User not found: ";
    private static final String INVALID_REFRESH_TOKEN_MESSAGE = "Invalid refresh token";

    @Value("${jwt.refresh-expiration}")
    private long refreshTokenExpiration;

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;

    @Transactional
    public String createRefreshToken(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException(USER_NOT_FOUND_MESSAGE + username));
        return issueRefreshToken(user);
    }

    @Transactional(readOnly = true)
    public Optional<String> findUsernameByToken(String token) {
        return refreshTokenRepository.findByToken(token)
                .filter(refreshToken -> refreshToken.getExpiryDate().isAfter(Instant.now()))
                .map(refreshToken -> refreshToken.getUser().getUsername());
    }

    @Transactional
    public String rotate(String token, User user) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(token)
                .orElseThrow(() -> new InvalidCredentialsException(INVALID_REFRESH_TOKEN_MESSAGE));

        if (refreshToken.getExpiryDate().isBefore(Instant.now())) {
            refreshTokenRepository.delete(refreshToken);
            throw new InvalidCredentialsException(INVALID_REFRESH_TOKEN_MESSAGE);
        }

        if (refreshToken.getUser().getId() != user.getId()) {
            throw new InvalidCredentialsException(INVALID_REFRESH_TOKEN_MESSAGE);
        }

        refreshTokenRepository.delete(refreshToken);
        return issueRefreshToken(user);
    }

    private String issueRefreshToken(User user) {
        RefreshToken refreshToken = RefreshToken.builder()
                .token(UUID.randomUUID().toString())
                .user(user)
                .expiryDate(Instant.now().plusMillis(refreshTokenExpiration))
                .build();
        return refreshTokenRepository.save(refreshToken).getToken();
    }
}

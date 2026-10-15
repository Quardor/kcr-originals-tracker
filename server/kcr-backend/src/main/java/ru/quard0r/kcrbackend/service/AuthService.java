package ru.quard0r.kcrbackend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.quard0r.kcrbackend.dto.request.LoginRequest;
import ru.quard0r.kcrbackend.dto.request.RegisterRequest;
import ru.quard0r.kcrbackend.dto.response.AuthResponse;
import ru.quard0r.kcrbackend.exception.CredentialsAlreadyExistsException;
import ru.quard0r.kcrbackend.exception.InvalidCredentialsException;
import ru.quard0r.kcrbackend.exception.UserNotFoundException;
import ru.quard0r.kcrbackend.model.Role;
import ru.quard0r.kcrbackend.model.User;
import ru.quard0r.kcrbackend.repository.UserRepository;
import ru.quard0r.kcrbackend.security.JwtService;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid credentials";
    private static final String INVALID_REFRESH_TOKEN_MESSAGE = "Invalid refresh token";

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());

        if (userRepository.existsByUsername(request.username())) {
            throw new CredentialsAlreadyExistsException("Username already taken");
        }
        if (email != null && userRepository.existsByEmail(email)) {
            throw new CredentialsAlreadyExistsException("Email already taken");
        }

        User user = User.builder()
                .username(request.username())
                .password(passwordEncoder.encode(request.password()))
                .email(email)
                .role(Role.OPERATOR)
                .build();

        userRepository.save(user);
        return issueTokens(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository
                .findByUsername(request.username())
                .orElseThrow(() -> new InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE);
        }

        return issueTokens(user);
    }

    @Transactional
    public AuthResponse refresh(String refreshToken) {
        String username = refreshTokenService.findUsernameByToken(refreshToken)
                .orElseThrow(() -> new InvalidCredentialsException(INVALID_REFRESH_TOKEN_MESSAGE));

        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        String newRefreshToken = refreshTokenService.rotate(refreshToken, user);

        return AuthResponse.builder()
                .accessToken(jwtService.generateAccessToken(user))
                .refreshToken(newRefreshToken)
                .build();
    }

    private AuthResponse issueTokens(User user) {
        return AuthResponse.builder()
                .accessToken(jwtService.generateAccessToken(user))
                .refreshToken(refreshTokenService.createRefreshToken(user.getUsername()))
                .build();
    }

    private String normalizeEmail(String email) {
        return email == null || email.isBlank() ? null : email.trim();
    }
}

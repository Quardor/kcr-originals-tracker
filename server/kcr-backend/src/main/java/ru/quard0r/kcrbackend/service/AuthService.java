package ru.quard0r.kcrbackend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.quard0r.kcrbackend.dto.request.LoginRequest;
import ru.quard0r.kcrbackend.dto.request.RegisterRequest;
import ru.quard0r.kcrbackend.dto.response.AuthResponse;
import ru.quard0r.kcrbackend.exception.CredentialsAlreadyExistsException;
import ru.quard0r.kcrbackend.exception.InvalidCredentialsException;
import ru.quard0r.kcrbackend.model.Role;
import ru.quard0r.kcrbackend.model.User;
import ru.quard0r.kcrbackend.repository.UserRepository;
import ru.quard0r.kcrbackend.security.CustomUserDetails;
import ru.quard0r.kcrbackend.security.JwtService;

@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid credentials";
    private static final String INVALID_REFRESH_TOKEN_MESSAGE = "Invalid refresh token";

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    private final AuthenticationManager authenticationManager;

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

        User user = userRepository.save(User.builder()
                .username(request.username())
                .password(passwordEncoder.encode(request.password()))
                .email(email)
                .role(Role.OPERATOR)
                .build());
        return issueTokens(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        } catch (AuthenticationException e) {
            throw new InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE);
        }
        User user = ((CustomUserDetails) authentication.getPrincipal()).user();
        return issueTokens(user);
    }

    public AuthResponse refresh(String refreshToken) {
        RefreshTokenService.RotationResult rotation = refreshTokenService.rotate(refreshToken)
                .orElseThrow(() -> new InvalidCredentialsException(INVALID_REFRESH_TOKEN_MESSAGE));

        return AuthResponse.builder()
                .accessToken(jwtService.generateAccessToken(rotation.user()))
                .refreshToken(rotation.refreshToken())
                .build();
    }

    private AuthResponse issueTokens(User user) {
        return AuthResponse.builder()
                .accessToken(jwtService.generateAccessToken(user))
                .refreshToken(refreshTokenService.createRefreshToken(user))
                .build();
    }

    private String normalizeEmail(String email) {
        return email == null || email.isBlank() ? null : email.trim();
    }
}

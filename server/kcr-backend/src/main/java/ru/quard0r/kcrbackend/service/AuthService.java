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
import ru.quard0r.kcrbackend.exception.DomainException;
import ru.quard0r.kcrbackend.exception.ErrorCode;
import ru.quard0r.kcrbackend.model.Role;
import ru.quard0r.kcrbackend.model.User;
import ru.quard0r.kcrbackend.repository.UserRepository;
import ru.quard0r.kcrbackend.security.CustomUserDetails;
import ru.quard0r.kcrbackend.security.JwtService;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    private final AuthenticationManager authenticationManager;

    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final AccountLockoutService accountLockoutService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.email());

        if (userRepository.existsByUsername(request.username())) {
            throw new DomainException(ErrorCode.USERNAME_ALREADY_TAKEN);
        }
        if (email != null && userRepository.existsByEmail(email)) {
            throw new DomainException(ErrorCode.EMAIL_ALREADY_TAKEN);
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
            accountLockoutService.registerFailedLogin(request.username());
            throw new DomainException(ErrorCode.INVALID_CREDENTIALS);
        }
        User user = ((CustomUserDetails) authentication.getPrincipal()).user();
        accountLockoutService.resetFailedLogins(user);
        return issueTokens(user);
    }

    public AuthResponse refresh(String refreshToken) {
        RefreshTokenService.RotationResult rotation = refreshTokenService.rotate(refreshToken)
                .orElseThrow(() -> new DomainException(ErrorCode.INVALID_REFRESH_TOKEN));

        return AuthResponse.builder()
                .accessToken(jwtService.generateAccessToken(rotation.user()))
                .refreshToken(rotation.refreshToken())
                .build();
    }

    @Transactional
    public void logout(User user) {
        refreshTokenService.revokeAllForUser(user);
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

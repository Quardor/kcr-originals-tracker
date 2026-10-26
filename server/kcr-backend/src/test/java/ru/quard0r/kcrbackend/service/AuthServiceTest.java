package ru.quard0r.kcrbackend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import ru.quard0r.kcrbackend.dto.request.LoginRequest;
import ru.quard0r.kcrbackend.dto.request.RegisterRequest;
import ru.quard0r.kcrbackend.dto.response.AuthResponse;
import ru.quard0r.kcrbackend.exception.CredentialsAlreadyExistsException;
import ru.quard0r.kcrbackend.exception.InvalidCredentialsException;
import ru.quard0r.kcrbackend.model.Role;
import ru.quard0r.kcrbackend.model.User;
import ru.quard0r.kcrbackend.repository.UserRepository;
import ru.quard0r.kcrbackend.security.JwtService;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String RAW_PASSWORD = "password123";
    private static final String HASHED_PASSWORD = "$2a$10$hashedpassword";

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private JwtService jwtService;

    @InjectMocks
    private AuthService authService;

    private User user() {
        return User.builder()
                .username("user")
                .password(HASHED_PASSWORD)
                .email("user@mail.com")
                .role(Role.OPERATOR)
                .build();
    }

    @Test
    void registerSavesOperatorUserWithHashedPasswordAndNormalizedEmail() {
        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(userRepository.existsByEmail("new@mail.com")).thenReturn(false);
        when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(HASHED_PASSWORD);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtService.generateAccessToken(any(User.class))).thenReturn("access-token");
        when(refreshTokenService.createRefreshToken(any(User.class))).thenReturn("refresh-token");

        AuthResponse response = authService.register(
                new RegisterRequest("newuser", RAW_PASSWORD, " new@mail.com "));

        assertEquals("access-token", response.accessToken());
        assertEquals("refresh-token", response.refreshToken());

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertEquals("newuser", saved.getUsername());
        assertEquals(HASHED_PASSWORD, saved.getPassword());
        assertEquals("new@mail.com", saved.getEmail());
        assertEquals(Role.OPERATOR, saved.getRole());
    }

    @Test
    void registerRejectsTakenUsername() {
        when(userRepository.existsByUsername("taken")).thenReturn(true);

        assertThrows(CredentialsAlreadyExistsException.class,
                () -> authService.register(new RegisterRequest("taken", RAW_PASSWORD, null)));
        verify(userRepository, never()).save(any());
    }

    @Test
    void registerRejectsTakenEmail() {
        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(userRepository.existsByEmail("taken@mail.com")).thenReturn(true);

        assertThrows(CredentialsAlreadyExistsException.class,
                () -> authService.register(new RegisterRequest("newuser", RAW_PASSWORD, "taken@mail.com")));
        verify(userRepository, never()).save(any());
    }

    @Test
    void registerTreatsBlankEmailAsAbsent() {
        when(userRepository.existsByUsername("newuser")).thenReturn(false);
        when(passwordEncoder.encode(RAW_PASSWORD)).thenReturn(HASHED_PASSWORD);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtService.generateAccessToken(any(User.class))).thenReturn("access-token");
        when(refreshTokenService.createRefreshToken(any(User.class))).thenReturn("refresh-token");

        authService.register(new RegisterRequest("newuser", RAW_PASSWORD, "   "));

        verify(userRepository, never()).existsByEmail(anyString());
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertNull(captor.getValue().getEmail());
    }

    @Test
    void loginReturnsTokensForValidCredentials() {
        User user = user();
        when(userRepository.findByUsername("user")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(RAW_PASSWORD, HASHED_PASSWORD)).thenReturn(true);
        when(jwtService.generateAccessToken(user)).thenReturn("access-token");
        when(refreshTokenService.createRefreshToken(user)).thenReturn("refresh-token");

        AuthResponse response = authService.login(new LoginRequest("user", RAW_PASSWORD));

        assertEquals("access-token", response.accessToken());
        assertEquals("refresh-token", response.refreshToken());
    }

    @Test
    void loginRejectsUnknownUser() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThrows(InvalidCredentialsException.class,
                () -> authService.login(new LoginRequest("ghost", RAW_PASSWORD)));
    }

    @Test
    void loginRejectsWrongPassword() {
        when(userRepository.findByUsername("user")).thenReturn(Optional.of(user()));
        when(passwordEncoder.matches(RAW_PASSWORD, HASHED_PASSWORD)).thenReturn(false);

        assertThrows(InvalidCredentialsException.class,
                () -> authService.login(new LoginRequest("user", RAW_PASSWORD)));
    }

    @Test
    void refreshReturnsNewTokenPairForValidRefreshToken() {
        User user = user();
        when(refreshTokenService.rotate("old-refresh"))
                .thenReturn(Optional.of(new RefreshTokenService.RotationResult(user, "new-refresh")));
        when(jwtService.generateAccessToken(user)).thenReturn("new-access");

        AuthResponse response = authService.refresh("old-refresh");

        assertEquals("new-access", response.accessToken());
        assertEquals("new-refresh", response.refreshToken());
    }

    @Test
    void refreshRejectsUnknownOrRevokedRefreshToken() {
        when(refreshTokenService.rotate("bad-refresh")).thenReturn(Optional.empty());

        assertThrows(InvalidCredentialsException.class, () -> authService.refresh("bad-refresh"));
        verifyNoInteractions(userRepository);
    }
}

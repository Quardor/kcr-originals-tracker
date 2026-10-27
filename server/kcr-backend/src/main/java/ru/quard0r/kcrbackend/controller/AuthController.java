package ru.quard0r.kcrbackend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.quard0r.kcrbackend.dto.request.LoginRequest;
import ru.quard0r.kcrbackend.dto.request.RefreshRequest;
import ru.quard0r.kcrbackend.dto.request.RegisterRequest;
import ru.quard0r.kcrbackend.dto.response.AuthResponse;
import ru.quard0r.kcrbackend.service.AuthService;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request)
    {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request)
    {
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request)
    {
        return ResponseEntity.ok(authService.refresh(request.refreshToken()));
    }
}

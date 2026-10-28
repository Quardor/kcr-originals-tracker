package ru.quard0r.kcrbackend.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.quard0r.kcrbackend.dto.request.LoginRequest;
import ru.quard0r.kcrbackend.dto.request.RefreshRequest;
import ru.quard0r.kcrbackend.dto.request.RegisterRequest;
import ru.quard0r.kcrbackend.dto.response.AuthResponse;
import ru.quard0r.kcrbackend.security.AuthRateLimiter;
import ru.quard0r.kcrbackend.security.CustomUserDetails;
import ru.quard0r.kcrbackend.service.AuthService;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private final AuthService authService;

    private final AuthRateLimiter authRateLimiter;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest httpRequest)
    {
        authRateLimiter.check(request.username(), clientIp(httpRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest)
    {
        authRateLimiter.check(request.username(), clientIp(httpRequest));
        return ResponseEntity.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request)
    {
        return ResponseEntity.ok(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal CustomUserDetails userDetails)
    {
        authService.logout(userDetails.user());
        return ResponseEntity.noContent().build();
    }

    // За доверенным прокси реальный адрес клиента — только в X-Forwarded-For;
    // при прямом доступе прокси-заголовок можно подделать, но ключ лимита — пара
    // username+IP, и username-часть ограничивает перебор одиночного аккаунта.
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader(FORWARDED_FOR_HEADER);
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

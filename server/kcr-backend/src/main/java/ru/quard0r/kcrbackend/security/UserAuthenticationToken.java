package ru.quard0r.kcrbackend.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * Результат конвертации access-токена. От `JwtAuthenticationToken` из Spring Security
 * отличается principal'ом: `CustomUserDetails` (сущность внутри), как его читают
 * контроллеры (`@AuthenticationPrincipal`) и `AuthService`.
 */
public class UserAuthenticationToken extends AbstractAuthenticationToken {

    private final CustomUserDetails principal;

    public UserAuthenticationToken(CustomUserDetails principal) {
        super(principal.getAuthorities());
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public Object getPrincipal() {
        return principal;
    }

    @Override
    public Object getCredentials() {
        return null;
    }
}

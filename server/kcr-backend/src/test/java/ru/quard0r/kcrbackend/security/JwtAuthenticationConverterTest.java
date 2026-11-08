package ru.quard0r.kcrbackend.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import ru.quard0r.kcrbackend.users.model.Role;
import ru.quard0r.kcrbackend.users.model.User;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationConverterTest {

    @Mock
    private CustomUserDetailsService userDetailsService;

    @InjectMocks
    private JwtAuthenticationConverter converter;

    private Jwt jwt(String subject) {
        return Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    private CustomUserDetails userDetails(Role role) {
        return new CustomUserDetails(User.builder()
                .id(42L)
                .username("user")
                .password("hash")
                .email("user@mail.com")
                .role(role)
                .build());
    }

    @Test
    void convertsValidTokenToAuthenticatedPrincipalWithRoleAuthorities() {
        when(userDetailsService.loadUserById(42L)).thenReturn(userDetails(Role.ADMIN));

        AbstractAuthenticationToken authentication = converter.convert(jwt("42"));

        assertTrue(authentication.isAuthenticated());
        List<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
        assertEquals(List.of("ROLE_ADMIN"), authorities);
        CustomUserDetails principal = assertInstanceOf(CustomUserDetails.class,
                authentication.getPrincipal());
        assertEquals(42L, principal.user().getId());
    }

    @Test
    void legacyUsernameSubjectIsRejected() {
        // До смены subject на id (п. 13 §10) subject был username — такие токены невалидны.
        assertThrows(InvalidBearerTokenException.class, () -> converter.convert(jwt("user")));
        verifyNoInteractions(userDetailsService);
    }

    @Test
    void unknownUserIsRejected() {
        when(userDetailsService.loadUserById(42L))
                .thenThrow(new UsernameNotFoundException("User not found: 42"));

        assertThrows(InvalidBearerTokenException.class, () -> converter.convert(jwt("42")));
    }

    @Test
    void principalIsServedFromCacheWithinTtl() {
        when(userDetailsService.loadUserById(42L)).thenReturn(userDetails(Role.OPERATOR));

        converter.convert(jwt("42"));
        converter.convert(jwt("42"));

        verify(userDetailsService, times(1)).loadUserById(42L);
    }
}

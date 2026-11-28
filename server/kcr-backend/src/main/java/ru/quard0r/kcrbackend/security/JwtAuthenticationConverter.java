package ru.quard0r.kcrbackend.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class JwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final Duration PRINCIPAL_CACHE_TTL = Duration.ofSeconds(30);
    private static final long PRINCIPAL_CACHE_MAX_SIZE = 10_000;

    private final Cache<Long, CustomUserDetails> principals = Caffeine.newBuilder()
            .maximumSize(PRINCIPAL_CACHE_MAX_SIZE)
            .expireAfterWrite(PRINCIPAL_CACHE_TTL)
            .build();

    private final CustomUserDetailsService userDetailsService;

    public JwtAuthenticationConverter(CustomUserDetailsService userDetailsService) {
        this.userDetailsService = userDetailsService;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) throws AuthenticationException {
        return new UserAuthenticationToken(loadPrincipal(parseUserId(jwt)));
    }

    private long parseUserId(Jwt jwt) {
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw new InvalidBearerTokenException("Token subject is not a user id");
        }
    }

    private CustomUserDetails loadPrincipal(long userId) {
        CustomUserDetails cached = principals.getIfPresent(userId);
        if (cached != null) {
            return cached;
        }
        try {
            CustomUserDetails loaded = userDetailsService.loadUserById(userId);
            principals.put(userId, loaded);
            return loaded;
        } catch (UsernameNotFoundException e) {
            throw new InvalidBearerTokenException("Unknown user: " + userId);
        }
    }
}

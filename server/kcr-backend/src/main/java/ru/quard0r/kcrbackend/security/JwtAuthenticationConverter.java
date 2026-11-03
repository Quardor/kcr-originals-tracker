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

/**
 * Конвертер ресурсного сервера: валидированный (подпись/срок — NimbusJwtDecoder) токен →
 * аутентификация с principal `CustomUserDetails`. Пользователь загружается по id из subject
 * через Caffeine-кэш с коротким TTL — горячий путь не ходит в БД на каждый запрос, при этом
 * удаление пользователя и смена роли подхватываются в пределах TTL. Любая неудача —
 * AuthenticationException → 401 от RestAuthenticationEntryPoint (не 500).
 */
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
            // Токены, выданные до перевода subject на id (п. 13 §10), невалидны.
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
            // Подпись верна, но пользователя больше нет — токен не принимается.
            throw new InvalidBearerTokenException("Unknown user: " + userId);
        }
    }
}

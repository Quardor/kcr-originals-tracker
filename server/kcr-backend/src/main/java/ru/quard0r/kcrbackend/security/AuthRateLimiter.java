package ru.quard0r.kcrbackend.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import ru.quard0r.kcrbackend.exception.DomainException;
import ru.quard0r.kcrbackend.exception.ErrorCode;

import java.time.Duration;

/**
 * Rate limiting публичных эндпоинтов аутентификации (антибрутфорс): token bucket
 * (Bucket4j) на пару username+IP. Бакеты живут в памяти инстанса — при нескольких
 * инстансах каждый считает лимит независимо (общий store — при необходимости позже).
 */
@Component
public class AuthRateLimiter {

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterAccess(Duration.ofHours(1))
            .build();

    @Value("${app.security.auth-rate-limit.capacity:10}")
    private int capacity;

    @Value("${app.security.auth-rate-limit.refill-duration:1m}")
    private Duration refillDuration;

    public void check(String username, String ip) {
        Bucket bucket = buckets.get(username + "|" + ip, key -> newBucket());
        if (!bucket.tryConsume(1)) {
            throw new DomainException(ErrorCode.TOO_MANY_AUTH_ATTEMPTS);
        }
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(limit -> limit.capacity(capacity).refillIntervally(capacity, refillDuration))
                .build();
    }
}

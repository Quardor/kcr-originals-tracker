package ru.quard0r.kcrbackend.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.quard0r.kcrbackend.exception.DomainException;
import ru.quard0r.kcrbackend.exception.ErrorCode;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthRateLimiterTest {

    private AuthRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new AuthRateLimiter();
        ReflectionTestUtils.setField(rateLimiter, "capacity", 3);
        ReflectionTestUtils.setField(rateLimiter, "refillDuration", Duration.ofMinutes(1));
    }

    @Test
    void allowsUpToCapacityRequestsThenRejects() {
        for (int i = 0; i < 3; i++) {
            assertDoesNotThrow(() -> rateLimiter.check("user", "10.0.0.1"));
        }
        DomainException exception = assertThrows(DomainException.class,
                () -> rateLimiter.check("user", "10.0.0.1"));
        assertEquals(ErrorCode.TOO_MANY_AUTH_ATTEMPTS, exception.getErrorCode());
    }

    @Test
    void eachUsernameAndIpPairHasItsOwnBucket() {
        for (int i = 0; i < 3; i++) {
            rateLimiter.check("user", "10.0.0.1");
        }
        assertThrows(DomainException.class, () -> rateLimiter.check("user", "10.0.0.1"));

        assertDoesNotThrow(() -> rateLimiter.check("user", "10.0.0.2"));
        assertDoesNotThrow(() -> rateLimiter.check("other-user", "10.0.0.1"));
    }
}

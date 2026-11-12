package ru.quard0r.kcrbackend.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityConfigPasswordEncoderTest {

    private static final String RAW_PASSWORD = "s3cret-P4ss";

    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        passwordEncoder = new SecurityConfig().passwordEncoder();
    }

    @Test
    void encodePrefixesHashWithAlgorithmId() {
        String hash = passwordEncoder.encode(RAW_PASSWORD);

        assertTrue(hash.startsWith("{bcrypt}$2"));
    }

    @Test
    void matchesPrefixedHash() {
        String hash = passwordEncoder.encode(RAW_PASSWORD);

        assertTrue(passwordEncoder.matches(RAW_PASSWORD, hash));
        assertFalse(passwordEncoder.matches("wrong", hash));
    }

    @Test
    void matchesLegacyHashWithoutPrefix() {
        String legacyHash = new BCryptPasswordEncoder().encode(RAW_PASSWORD);
        assertFalse(legacyHash.startsWith("{"));

        assertTrue(passwordEncoder.matches(RAW_PASSWORD, legacyHash));
        assertFalse(passwordEncoder.matches("wrong", legacyHash));
    }
}

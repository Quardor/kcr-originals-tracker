package ru.quard0r.kcrbackend.dto.request;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EmailNormalizationTest {

    @Test
    void registerRequestNormalizesEmail() {
        assertEquals("user@mail.com",
                new RegisterRequest("user", "password123", " user@mail.com ").email());
        assertEquals("user@mail.com",
                new RegisterRequest("user", "password123", "user@mail.com").email());
        assertNull(new RegisterRequest("user", "password123", "   ").email());
        assertNull(new RegisterRequest("user", "password123", null).email());
    }
}

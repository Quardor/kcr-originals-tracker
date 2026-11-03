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

    @Test
    void clientCreateRequestNormalizesContactEmail() {
        assertEquals("contact@mail.com",
                new ClientCreateRequest("ABC12345", "123456789012", null, "Organization",
                        null, null, null, null, null, null, null, null, null, null,
                        " contact@mail.com ").contactEmail());
        assertNull(new ClientCreateRequest("ABC12345", "123456789012", null, "Organization",
                null, null, null, null, null, null, null, null, null, null, "   ").contactEmail());
        assertNull(new ClientCreateRequest("ABC12345", "123456789012", null, "Organization",
                null, null, null, null, null, null, null, null, null, null, null).contactEmail());
    }

    @Test
    void clientUpdateRequestNormalizesContactEmail() {
        assertEquals("contact@mail.com",
                new ClientUpdateRequest(null, null, null, null, null, null, null,
                        null, null, null, null, null, null, " contact@mail.com ").contactEmail());
        assertNull(new ClientUpdateRequest(null, null, null, null, null, null, null,
                null, null, null, null, null, null, "   ").contactEmail());
    }
}

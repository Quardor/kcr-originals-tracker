package ru.quard0r.kcrbackend.clients.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import ru.quard0r.kcrbackend.common.validation.Inn;
import ru.quard0r.kcrbackend.common.validation.KcrCode;
import ru.quard0r.kcrbackend.common.validation.Kpp;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    record Sample(@Inn String inn, @Kpp String kpp, @KcrCode String kcrCode) {
    }

    @Test
    void innAcceptsTenAndTwelveDigitsOnly() {
        assertTrue(violations(new Sample("1234567890", null, null)).isEmpty());
        assertTrue(violations(new Sample("123456789012", null, null)).isEmpty());

        assertEquals(1, violations(new Sample("12345678901", null, null)).size());
        assertEquals(1, violations(new Sample("1234567890123", null, null)).size());
        assertEquals(1, violations(new Sample("12345678901a", null, null)).size());
    }

    @Test
    void kppAcceptsNineDigitsOnly() {
        assertTrue(violations(new Sample(null, "123456789", null)).isEmpty());
        assertEquals(1, violations(new Sample(null, "12345678", null)).size());
        assertEquals(1, violations(new Sample(null, "1234567890", null)).size());
    }

    @Test
    void kcrCodeAcceptsCanonicalFormOnly() {
        assertTrue(violations(new Sample(null, null, "ABC12345")).isEmpty());

        assertEquals(1, violations(new Sample(null, null, "abc12345")).size());
        assertEquals(1, violations(new Sample(null, null, "ABCD12345")).size());
        assertEquals(1, violations(new Sample(null, null, "AB1234")).size());
    }

    @Test
    void constraintsTreatNullAsValid() {
        assertTrue(violations(new Sample(null, null, null)).isEmpty());
    }

    @Test
    void createRequestNormalizesKcrCodeBeforeValidation() {
        ClientCreateRequest request = new ClientCreateRequest(
                " abc12345 ", "123456789012", null, "Organization",
                null, null, null, null, null, null, null, null, null, null, null);

        assertEquals("ABC12345", request.kcrCode());
        assertTrue(validator.validate(request).isEmpty());
    }

    @Test
    void createRequestRejectsImportIncompatibleInn() {
        ClientCreateRequest request = new ClientCreateRequest(
                "ABC12345", "12345678901", null, "Organization",
                null, null, null, null, null, null, null, null, null, null, null);
        assertEquals(1, validator.validate(request).size());
    }

    @Test
    void updateRequestValidatesInnAndKppWithSameRules() {
        ClientUpdateRequest valid = new ClientUpdateRequest(
                "1234567890", "987654321", null, null, null, null, null, null,
                null, null, null, null, null, null);
        assertTrue(validator.validate(valid).isEmpty());

        Set<ConstraintViolation<ClientUpdateRequest>> violations = validator.validate(
                new ClientUpdateRequest(
                        "123", null, null, null, null, null, null, null,
                        null, null, null, null, null, null));
        assertEquals(1, violations.size());
        assertEquals("inn", violations.iterator().next().getPropertyPath().toString());
    }

    private Set<ConstraintViolation<Sample>> violations(Sample sample) {
        return validator.validate(sample);
    }
}

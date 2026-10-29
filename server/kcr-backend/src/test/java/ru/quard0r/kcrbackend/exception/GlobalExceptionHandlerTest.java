package ru.quard0r.kcrbackend.exception;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import ru.quard0r.kcrbackend.dto.response.ErrorResponse;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void domainExceptionMapsToCodeMessageAndStatus() {
        ResponseEntity<ErrorResponse> response =
                handler.exceptionHandler(new DomainException(ErrorCode.CLIENT_NOT_FOUND));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("CLIENT_NOT_FOUND", response.getBody().code());
        assertEquals("Client not found", response.getBody().message());
        assertNotNull(response.getBody().timestamp());
    }

    @Test
    void integrityViolationMapsKnownUniqueConstraintsToSpecificCodes() {
        assertEquals("USERNAME_ALREADY_TAKEN", handleIntegrityViolation("uk_users_username").code());
        assertEquals("EMAIL_ALREADY_TAKEN", handleIntegrityViolation("uk_users_email").code());
        assertEquals("CLIENT_ALREADY_EXISTS", handleIntegrityViolation("uk_clients_kcr_code").code());
    }

    @Test
    void integrityViolationMapsKnownConstraintsToConflictStatus() {
        ResponseEntity<ErrorResponse> response = handler.exceptionHandler(
                integrityViolation("uk_users_username"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    }

    @Test
    void integrityViolationWithUnknownConstraintFallsBackToDataConflict() {
        ResponseEntity<ErrorResponse> response = handler.exceptionHandler(
                integrityViolation("clients_client_type_check"));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("DATA_CONFLICT", response.getBody().code());
    }

    @Test
    void integrityViolationWithoutConstraintNameFallsBackToDataConflict() {
        DataIntegrityViolationException e =
                new DataIntegrityViolationException("insert failed", new SQLException("boom"));

        ResponseEntity<ErrorResponse> response = handler.exceptionHandler(e);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("DATA_CONFLICT", response.getBody().code());
    }

    @Test
    void integrityViolationParsesConstraintNameFromDriverMessage() {
        // Без hibernate-обёртки в цепочке причин имя констрейнта извлекается
        // из сообщения драйвера.
        SQLException driver = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"uk_users_username\"");
        DataIntegrityViolationException e =
                new DataIntegrityViolationException("insert failed", driver);

        ResponseEntity<ErrorResponse> response = handler.exceptionHandler(e);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("USERNAME_ALREADY_TAKEN", response.getBody().code());
    }

    private DataIntegrityViolationException integrityViolation(String constraintName) {
        SQLException driver = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"" + constraintName + "\"");
        ConstraintViolationException hibernate =
                new ConstraintViolationException("could not execute statement", driver, constraintName);
        return new DataIntegrityViolationException("insert failed", hibernate);
    }

    private ErrorResponse handleIntegrityViolation(String constraintName) {
        return handler.exceptionHandler(integrityViolation(constraintName)).getBody();
    }
}

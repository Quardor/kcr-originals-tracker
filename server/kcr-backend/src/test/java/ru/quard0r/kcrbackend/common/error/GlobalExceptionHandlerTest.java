package ru.quard0r.kcrbackend.common.error;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import ru.quard0r.kcrbackend.common.error.ErrorResponse;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void integrityViolationMapsCaseInsensitiveUniqueIndexesToSpecificCodes() {
        // Функциональные unique-индексы по lower(...) из V7 — имя индекса в нарушении.
        assertEquals("USERNAME_ALREADY_TAKEN",
                handleIntegrityViolation("idx_users_username_lower").code());
        assertEquals("EMAIL_ALREADY_TAKEN",
                handleIntegrityViolation("idx_users_email_lower").code());
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
                "ERROR: duplicate key value violates unique constraint \"idx_users_username_lower\"");
        DataIntegrityViolationException e =
                new DataIntegrityViolationException("insert failed", driver);

        ResponseEntity<ErrorResponse> response = handler.exceptionHandler(e);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("USERNAME_ALREADY_TAKEN", response.getBody().code());
    }

    @Test
    void catchAllMapsUnexpectedExceptionToNeutralInternalError() {
        // Причина не раскрывается клиенту (стек — в лог): единый контракт 500.
        ResponseEntity<ErrorResponse> response =
                handler.exceptionHandler(new IllegalStateException("boom with details"));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("INTERNAL_ERROR", response.getBody().code());
        assertEquals("Internal server error", response.getBody().message());
    }

    @Test
    void accessDeniedIsRethrownForSecurityFilterChain() {
        // 403 решается ExceptionTranslationFilter: переброс из advice сохраняет
        // семантику авторизации (иначе catch-all превратил бы её в 500).
        assertThrows(AccessDeniedException.class,
                () -> handler.exceptionHandler(new AccessDeniedException("denied")));
    }

    @Test
    void domainExceptionIsNotCaughtByCatchAll() {
        // Более специфичный хендлер побеждает catch-all — доменный контракт сохранён.
        ResponseEntity<ErrorResponse> response = assertDoesNotThrow(() ->
                handler.exceptionHandler(new DomainException(ErrorCode.INVALID_CREDENTIALS)));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals("INVALID_CREDENTIALS", response.getBody().code());
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

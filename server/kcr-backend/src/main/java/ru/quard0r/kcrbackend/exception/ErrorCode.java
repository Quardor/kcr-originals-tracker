package ru.quard0r.kcrbackend.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Реестр кодов ошибок API: имя константы — машиночитаемый код из ErrorResponse,
 * HTTP-статус и сообщение по умолчанию задаются в одном месте (см. §6 ARCHITECTURE.md).
 */
@Getter
public enum ErrorCode {
    // аутентификация
    AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, "Authentication required"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Invalid credentials"),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "Invalid refresh token"),
    USERNAME_ALREADY_TAKEN(HttpStatus.CONFLICT, "Username already taken"),
    EMAIL_ALREADY_TAKEN(HttpStatus.CONFLICT, "Email already taken"),
    TOO_MANY_AUTH_ATTEMPTS(HttpStatus.TOO_MANY_REQUESTS,
            "Too many authentication attempts, please try again later"),

    // клиенты
    CLIENT_NOT_FOUND(HttpStatus.NOT_FOUND, "Client not found"),
    CLIENT_ALREADY_EXISTS(HttpStatus.CONFLICT, "Client with this KCR code already exists"),
    BLANK_ORGANIZATION_NAME(HttpStatus.BAD_REQUEST, "Organization name must not be blank"),
    INVALID_SORT_FIELD(HttpStatus.BAD_REQUEST, "Invalid sort field"),
    INVALID_SORT_DIRECTION(HttpStatus.BAD_REQUEST, "Invalid sort direction"),

    // запрос не прошёл разбор/валидацию (ошибки фреймворка, GlobalExceptionHandler)
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    MALFORMED_REQUEST_BODY(HttpStatus.BAD_REQUEST, "Malformed request body"),
    INVALID_REQUEST_PARAMETER(HttpStatus.BAD_REQUEST, "Invalid request parameter"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    DATA_CONFLICT(HttpStatus.CONFLICT, "Data integrity violation"),

    // внутренние
    SHA256_UNAVAILABLE(HttpStatus.INTERNAL_SERVER_ERROR, "SHA-256 digest is not available in the JRE");

    private final HttpStatus httpStatus;
    private final String message;

    ErrorCode(HttpStatus httpStatus, String message) {
        this.httpStatus = httpStatus;
        this.message = message;
    }
}

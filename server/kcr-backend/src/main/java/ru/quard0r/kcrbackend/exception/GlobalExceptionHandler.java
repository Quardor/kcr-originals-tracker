package ru.quard0r.kcrbackend.exception;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import ru.quard0r.kcrbackend.dto.response.ErrorResponse;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // Имена констрейнтов уникальности — из миграций V1__init.sql / V3__client.sql;
    // гонка «exists-проверка + save» распознаётся по нарушенному констрейнту.
    private static final String UK_USERS_USERNAME = "uk_users_username";
    private static final String UK_USERS_EMAIL = "uk_users_email";
    private static final String UK_CLIENTS_KCR_CODE = "uk_clients_kcr_code";

    private static final Pattern CONSTRAINT_NAME_PATTERN = Pattern.compile("constraint \"([^\"]+)\"");

    @ExceptionHandler
    ResponseEntity<ErrorResponse> exceptionHandler(DomainException e) {
        ErrorCode errorCode = e.getErrorCode();
        return respond(errorCode, new ErrorResponse(errorCode));
    }

    @ExceptionHandler
    ResponseEntity<ErrorResponse> exceptionHandler(MethodArgumentNotValidException e) {
        String fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        ErrorCode errorCode = ErrorCode.VALIDATION_FAILED;
        return respond(errorCode, new ErrorResponse(errorCode, "Validation failed: " + fieldErrors));
    }

    @ExceptionHandler
    ResponseEntity<ErrorResponse> exceptionHandler(HttpMessageNotReadableException e) {
        return respond(ErrorCode.MALFORMED_REQUEST_BODY, new ErrorResponse(ErrorCode.MALFORMED_REQUEST_BODY));
    }

    @ExceptionHandler
    ResponseEntity<ErrorResponse> exceptionHandler(NoResourceFoundException e) {
        return respond(ErrorCode.RESOURCE_NOT_FOUND, new ErrorResponse(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @ExceptionHandler
    ResponseEntity<ErrorResponse> exceptionHandler(MethodArgumentTypeMismatchException e) {
        return respond(ErrorCode.INVALID_REQUEST_PARAMETER,
                new ErrorResponse(ErrorCode.INVALID_REQUEST_PARAMETER));
    }

    @ExceptionHandler
    ResponseEntity<ErrorResponse> exceptionHandler(DataIntegrityViolationException e) {
        ErrorCode errorCode = integrityErrorCode(constraintName(e));
        return respond(errorCode, new ErrorResponse(errorCode));
    }

    private ErrorCode integrityErrorCode(String constraintName) {
        if (constraintName == null) {
            return ErrorCode.DATA_CONFLICT;
        }
        return switch (constraintName) {
            case UK_USERS_USERNAME -> ErrorCode.USERNAME_ALREADY_TAKEN;
            case UK_USERS_EMAIL -> ErrorCode.EMAIL_ALREADY_TAKEN;
            case UK_CLIENTS_KCR_CODE -> ErrorCode.CLIENT_ALREADY_EXISTS;
            default -> ErrorCode.DATA_CONFLICT;
        };
    }

    // Hibernate-обёртка несёт имя констрейнта; если её нет в цепочке причин —
    // парсим сообщение драйвера («... violates unique constraint "uk_..."»).
    private String constraintName(DataIntegrityViolationException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException hibernate
                    && hibernate.getConstraintName() != null) {
                return hibernate.getConstraintName();
            }
        }
        Matcher matcher = CONSTRAINT_NAME_PATTERN.matcher(
                String.valueOf(e.getMostSpecificCause().getMessage()));
        return matcher.find() ? matcher.group(1) : null;
    }

    private ResponseEntity<ErrorResponse> respond(ErrorCode errorCode, ErrorResponse body) {
        return new ResponseEntity<>(body, errorCode.getHttpStatus());
    }
}

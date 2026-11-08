package ru.quard0r.kcrbackend.common.error;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import ru.quard0r.kcrbackend.common.error.ErrorResponse;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String UK_USERS_USERNAME = "uk_users_username";
    private static final String UK_USERS_EMAIL = "uk_users_email";
    private static final String IDX_USERS_USERNAME_LOWER = "idx_users_username_lower";
    private static final String IDX_USERS_EMAIL_LOWER = "idx_users_email_lower";
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
    ResponseEntity<ErrorResponse> exceptionHandler(HandlerMethodValidationException e) {
        String parameterErrors = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .map(MessageSourceResolvable::getDefaultMessage)
                .collect(Collectors.joining("; "));
        ErrorCode errorCode = ErrorCode.VALIDATION_FAILED;
        return respond(errorCode, new ErrorResponse(errorCode, "Validation failed: " + parameterErrors));
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
    ResponseEntity<ErrorResponse> exceptionHandler(MissingServletRequestParameterException e) {
        return respond(ErrorCode.INVALID_REQUEST_PARAMETER, new ErrorResponse(ErrorCode.INVALID_REQUEST_PARAMETER,
                "Missing request parameter: " + e.getParameterName()));
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
            case UK_USERS_USERNAME, IDX_USERS_USERNAME_LOWER -> ErrorCode.USERNAME_ALREADY_TAKEN;
            case UK_USERS_EMAIL, IDX_USERS_EMAIL_LOWER -> ErrorCode.EMAIL_ALREADY_TAKEN;
            case UK_CLIENTS_KCR_CODE -> ErrorCode.CLIENT_ALREADY_EXISTS;
            default -> ErrorCode.DATA_CONFLICT;
        };
    }

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

    @ExceptionHandler
    void exceptionHandler(AccessDeniedException e) throws AccessDeniedException {
        throw e;
    }

    @ExceptionHandler
    ResponseEntity<ErrorResponse> exceptionHandler(Exception e) {
        log.error("Unhandled exception", e);
        return respond(ErrorCode.INTERNAL_ERROR, new ErrorResponse(ErrorCode.INTERNAL_ERROR));
    }

    private ResponseEntity<ErrorResponse> respond(ErrorCode errorCode, ErrorResponse body) {
        return new ResponseEntity<>(body, errorCode.getHttpStatus());
    }
}

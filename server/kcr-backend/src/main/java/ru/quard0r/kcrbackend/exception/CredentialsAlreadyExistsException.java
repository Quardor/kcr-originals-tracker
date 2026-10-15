package ru.quard0r.kcrbackend.exception;

import org.springframework.http.HttpStatus;
import ru.quard0r.kcrbackend.exception.base.BadRequestException;

public class CredentialsAlreadyExistsException extends BadRequestException {

    private static final HttpStatus STATUS = HttpStatus.CONFLICT;

    public CredentialsAlreadyExistsException(String message) {
        super(message);
    }

    public HttpStatus getHttpStatus() {
        return STATUS;
    }
}

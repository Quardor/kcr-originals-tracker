package ru.quard0r.kcrbackend.exception;

import org.springframework.http.HttpStatus;
import ru.quard0r.kcrbackend.exception.base.BadRequestException;

public class InvalidCredentialsException extends BadRequestException {

    private static final HttpStatus STATUS = HttpStatus.UNAUTHORIZED;

    public InvalidCredentialsException(String message) {
        super(message);
    }

    @Override
    public HttpStatus getHttpStatus() {
        return STATUS;
    }
}

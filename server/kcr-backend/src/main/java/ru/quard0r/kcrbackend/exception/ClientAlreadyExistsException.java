package ru.quard0r.kcrbackend.exception;

import org.springframework.http.HttpStatus;
import ru.quard0r.kcrbackend.exception.base.BadRequestException;

public class ClientAlreadyExistsException extends BadRequestException {

    private static final HttpStatus STATUS = HttpStatus.CONFLICT;

    public ClientAlreadyExistsException(String message) {
        super(message);
    }

    @Override
    public HttpStatus getHttpStatus() {
        return STATUS;
    }
}

package ru.quard0r.kcrbackend.exception;

import org.springframework.http.HttpStatus;
import ru.quard0r.kcrbackend.exception.base.BadRequestException;

public class ClientNotFoundException extends BadRequestException {

    private static final HttpStatus STATUS = HttpStatus.NOT_FOUND;

    public ClientNotFoundException(String message) {
        super(message);
    }

    @Override
    public HttpStatus getHttpStatus() {
        return STATUS;
    }
}

package ru.quard0r.kcrbackend.exception;

import org.springframework.http.HttpStatus;
import ru.quard0r.kcrbackend.exception.base.HttpException;

public class TooManyRequestsException extends HttpException {

    private static final HttpStatus STATUS = HttpStatus.TOO_MANY_REQUESTS;

    public TooManyRequestsException(String message) {
        super(message);
    }

    @Override
    public HttpStatus getHttpStatus() {
        return STATUS;
    }
}

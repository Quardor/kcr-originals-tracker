package ru.quard0r.kcrbackend.exception.base;

import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

@NoArgsConstructor
public abstract class HttpException extends BasicException {

    public HttpException(String message) {
        super(message);
    }

    public abstract HttpStatus getHttpStatus();
}

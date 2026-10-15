package ru.quard0r.kcrbackend.exception.base;

import lombok.NoArgsConstructor;

@NoArgsConstructor
public class BasicException extends RuntimeException {

    public BasicException(String message) {
        super(message);
    }
}

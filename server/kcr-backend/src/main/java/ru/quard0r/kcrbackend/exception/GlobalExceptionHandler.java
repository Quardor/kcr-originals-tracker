package ru.quard0r.kcrbackend.exception;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import ru.quard0r.kcrbackend.dto.response.ErrorResponse;
import ru.quard0r.kcrbackend.exception.base.HttpException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler
    private ResponseEntity<ErrorResponse> exceptionHandler(HttpException e) {
        return new ResponseEntity<>(new ErrorResponse(e.getMessage()), e.getHttpStatus());
    }

    @ExceptionHandler
    private ResponseEntity<ErrorResponse> exceptionHandler(MethodArgumentNotValidException e) {
        String fieldErrors = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return new ResponseEntity<>(new ErrorResponse("Validation failed: " + fieldErrors), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler
    private ResponseEntity<ErrorResponse> exceptionHandler(HttpMessageNotReadableException e) {
        return new ResponseEntity<>(new ErrorResponse("Malformed request body"), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler
    private ResponseEntity<ErrorResponse> exceptionHandler(NoResourceFoundException e) {
        return new ResponseEntity<>(new ErrorResponse("Resource not found"), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler
    private ResponseEntity<ErrorResponse> exceptionHandler(DataIntegrityViolationException e) {
        return new ResponseEntity<>(new ErrorResponse("Username or email already taken"), HttpStatus.CONFLICT);
    }
}

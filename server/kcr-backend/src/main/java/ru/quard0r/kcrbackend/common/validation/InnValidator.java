package ru.quard0r.kcrbackend.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

public class InnValidator implements ConstraintValidator<Inn, String> {

    public static final Pattern PATTERN = Pattern.compile("^(\\d{10}|\\d{12})$");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || PATTERN.matcher(value).matches();
    }
}

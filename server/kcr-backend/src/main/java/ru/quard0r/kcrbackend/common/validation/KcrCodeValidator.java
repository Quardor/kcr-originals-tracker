package ru.quard0r.kcrbackend.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

public class KcrCodeValidator implements ConstraintValidator<KcrCode, String> {

    public static final String REGEX = "^[A-Z]{3}\\d{5}$";

    public static final Pattern PATTERN = Pattern.compile(REGEX);

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || PATTERN.matcher(value).matches();
    }
}

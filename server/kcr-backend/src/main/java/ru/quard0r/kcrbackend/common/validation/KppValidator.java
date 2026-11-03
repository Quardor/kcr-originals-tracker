package ru.quard0r.kcrbackend.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

public class KppValidator implements ConstraintValidator<Kpp, String> {

    public static final Pattern PATTERN = Pattern.compile("^\\d{9}$");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return value == null || PATTERN.matcher(value).matches();
    }
}

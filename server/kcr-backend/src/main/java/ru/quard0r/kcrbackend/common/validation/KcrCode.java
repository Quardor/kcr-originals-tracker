package ru.quard0r.kcrbackend.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = KcrCodeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface KcrCode {

    String message() default "KCR code must be 3 uppercase Latin letters followed by 5 digits";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

package ru.quard0r.kcrbackend.common;

import java.util.Locale;

public final class UserIdentityNormalizer {

    private UserIdentityNormalizer() {
    }

    public static String username(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    public static String email(String value) {
        String normalized = EmailNormalizer.normalize(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }
}

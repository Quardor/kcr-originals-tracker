package ru.quard0r.kcrbackend.common;

public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    public static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

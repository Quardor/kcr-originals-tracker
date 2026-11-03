package ru.quard0r.kcrbackend.common;

import java.util.Locale;

public final class KcrCodeNormalizer {

    private KcrCodeNormalizer() {
    }

    public static String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }
}

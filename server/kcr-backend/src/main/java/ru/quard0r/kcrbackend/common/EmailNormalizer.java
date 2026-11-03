package ru.quard0r.kcrbackend.common;

// Единственная копия правила нормализации email-полей запросов (п. 16 §10).
// Вызывается из компакт-конструкторов record'ов до Bean Validation.
public final class EmailNormalizer {

    private EmailNormalizer() {
    }

    public static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

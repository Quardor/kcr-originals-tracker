package ru.quard0r.kcrbackend.clients.events;

/**
 * Результат звонка — обязателен для события CALL (§11.1: сохранение результата
 * звонка). Справочник принят 2026-11-08 (вопрос 3 §11.7); содержательные детали
 * (договорённость, сроки) несут отдельные поля события, а не значения перечисления.
 */
public enum CallResult {
    CONNECTED,
    NO_ANSWER,
    CALLBACK_LATER,
    WRONG_NUMBER
}

package ru.quard0r.kcrbackend.clients.events;

/**
 * Статус применения санкции (§11.3). Справочник принят 2026-11-08 (вопрос 3 §11.7);
 * названия санкций — свободный текст, справочник доступных санкций заказчиком
 * не определён (§11.7).
 */
public enum SanctionStatus {
    PLANNED,
    APPLIED,
    CANCELLED
}

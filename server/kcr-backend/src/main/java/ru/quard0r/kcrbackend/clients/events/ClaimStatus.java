package ru.quard0r.kcrbackend.clients.events;

/**
 * Статус претензии. Дата получения клиентом — отдельное поле претензии
 * (received_by_client_at), статус RECEIVED лишь отмечает факт. Справочник принят
 * 2026-11-08 (вопрос 3 §11.7).
 */
public enum ClaimStatus {
    NEW,
    SENT,
    RECEIVED,
    SATISFIED,
    CANCELLED
}

package ru.quard0r.kcrbackend.clients.events;

/**
 * Тип записи единой истории клиента (§11.3). CALL/LETTER_SENT/COMMENT заводятся
 * вручную через API; остальные создаются сервисным слоем при соответствующих
 * мутациях (смена этапа, переназначение, загрузка данных, претензия, санкция)
 * и вручную недоступны.
 */
public enum ClientEventType {
    CALL,
    LETTER_SENT,
    COMMENT,
    STAGE_CHANGED,
    ASSIGNEE_CHANGED,
    IMPORT,
    CLAIM,
    SANCTION;

    public boolean isManuallyCreatable() {
        return this == CALL || this == LETTER_SENT || this == COMMENT;
    }
}

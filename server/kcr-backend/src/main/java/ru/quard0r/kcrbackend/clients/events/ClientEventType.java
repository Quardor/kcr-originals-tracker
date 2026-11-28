package ru.quard0r.kcrbackend.clients.events;

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

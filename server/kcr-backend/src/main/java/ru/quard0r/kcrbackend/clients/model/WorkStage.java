package ru.quard0r.kcrbackend.clients.model;

public enum WorkStage {
    NOT_IN_WORK,
    IN_POOL,
    FIRST_CALL,
    WAITING_CLIENT_DEADLINE,
    RECHECK,
    SECOND_CALL,
    CLAIM_WORK,
    COMPLETED;

    public boolean isWorkStage() {
        return this != NOT_IN_WORK && this != IN_POOL;
    }
}

package ru.quard0r.kcrbackend.clients;

import ru.quard0r.kcrbackend.clients.model.Client;
import ru.quard0r.kcrbackend.clients.model.WorkStage;
import ru.quard0r.kcrbackend.common.error.DomainException;
import ru.quard0r.kcrbackend.common.error.ErrorCode;
import ru.quard0r.kcrbackend.model.Role;
import ru.quard0r.kcrbackend.model.User;

/**
 * Правила смены этапа (решение по вопросу 1 §11.7, 2026-11-08): админ переводит
 * на любой этап любому клиенту; оператор — только на рабочие этапы
 * (FIRST_CALL…COMPLETED) и только клиентов, взятых им самим. Порядок переходов
 * не контролируется: последовательность может отличаться по клиентам (§11.2).
 * Администратор наследует возможности оператора (§11.1), поэтому для него
 * ограничений нет. Вызывается внутри транзакции: доступ к assignee ленивый.
 */
public final class StagePolicy {

    private StagePolicy() {
    }

    public static void assertChangeAllowed(User actor, Client client, WorkStage target) {
        if (actor.getRole() == Role.ADMIN) {
            return;
        }
        boolean ownClient = client.getAssignee() != null
                && actor.getId() == client.getAssignee().getId();
        if (!ownClient || !target.isWorkStage()) {
            throw new DomainException(ErrorCode.STAGE_CHANGE_FORBIDDEN);
        }
    }
}

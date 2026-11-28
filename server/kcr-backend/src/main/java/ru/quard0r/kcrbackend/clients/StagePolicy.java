package ru.quard0r.kcrbackend.clients;

import ru.quard0r.kcrbackend.clients.model.Client;
import ru.quard0r.kcrbackend.clients.model.WorkStage;
import ru.quard0r.kcrbackend.common.error.DomainException;
import ru.quard0r.kcrbackend.common.error.ErrorCode;
import ru.quard0r.kcrbackend.users.model.Role;
import ru.quard0r.kcrbackend.users.model.User;

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

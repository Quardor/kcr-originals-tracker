package ru.quard0r.kcrbackend.clients.dto;

import ru.quard0r.kcrbackend.clients.model.ClientType;
import ru.quard0r.kcrbackend.snapshots.model.DynamicsCategory;

import java.util.List;

/**
 * Критерии фильтрации клиентов для предпросмотра и составления списка на обзвон (§11.2, §11.4).
 * <p>
 * Все поля — nullable; отсутствие значения означает «без ограничения» по данному критерию.
 * Семантика {@code hasDebt=null} и {@code minUntransferredPercent=null} обрабатывается
 * в {@link ru.quard0r.kcrbackend.clients.WorklistService}.
 */
public record WorklistFilterRequest(
        String search,
        ClientType clientType,
        Boolean hasDebt,
        Integer minDebt,
        Double minUntransferredPercent,
        Double maxUntransferredPercent,
        Boolean lowDynamics,
        DynamicsCategory dynamicsCategory,
        List<Long> clientIds
) {
}

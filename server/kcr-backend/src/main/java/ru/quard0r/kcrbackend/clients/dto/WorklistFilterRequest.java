package ru.quard0r.kcrbackend.clients.dto;

import ru.quard0r.kcrbackend.clients.model.ClientType;
import ru.quard0r.kcrbackend.snapshots.model.DynamicsCategory;

import java.util.List;

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

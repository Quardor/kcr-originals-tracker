package ru.quard0r.kcrbackend.clients;

import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.quard0r.kcrbackend.clients.dto.CallListClientResponse;
import ru.quard0r.kcrbackend.clients.dto.CallListSummaryResponse;
import ru.quard0r.kcrbackend.clients.dto.WorklistFilterRequest;
import ru.quard0r.kcrbackend.clients.events.ClientEventService;
import ru.quard0r.kcrbackend.clients.events.ClientEventType;
import ru.quard0r.kcrbackend.clients.model.CallListItem;
import ru.quard0r.kcrbackend.clients.model.Client;
import ru.quard0r.kcrbackend.clients.model.WorkStage;
import ru.quard0r.kcrbackend.clients.repository.CallListItemRepository;
import ru.quard0r.kcrbackend.clients.repository.ClientRepository;
import ru.quard0r.kcrbackend.snapshots.DynamicsService;
import ru.quard0r.kcrbackend.snapshots.dto.ClientDebtInfo;
import ru.quard0r.kcrbackend.snapshots.model.DocumentTransferSnapshot;
import ru.quard0r.kcrbackend.snapshots.model.DynamicsCategory;
import ru.quard0r.kcrbackend.snapshots.repository.DocumentTransferSnapshotRepository;
import ru.quard0r.kcrbackend.users.model.User;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorklistService
{
    private final ClientRepository clientRepository;
    private final DocumentTransferSnapshotRepository snapshotRepository;
    private final CallListItemRepository callListItemRepository;
    private final DynamicsService dynamicsService;
    private final ClientEventService eventService;

    @Transactional(readOnly = true)
    public List<CallListClientResponse> previewCallList(WorklistFilterRequest request)
    {
        List<Client> clients = fetchFilteredClients(request);
        if (clients.isEmpty())
        {
            return Collections.emptyList();
        }

        List<Long> clientIds = clients.stream().map(Client::getId).toList();
        Map<Long, List<DocumentTransferSnapshot>> snapshotsByClientId = loadSnapshotsByClientId(clientIds);

        List<CallListClientResponse> result = new ArrayList<>();
        for (Client client : clients)
        {
            List<DocumentTransferSnapshot> snapshots =
                    snapshotsByClientId.getOrDefault(client.getId(), Collections.emptyList());
            ClientDebtInfo debtInfo = dynamicsService.getClientDebtInfo(client, snapshots);

            if (!matchesDebtFilters(request, debtInfo))
            {
                continue;
            }

            result.add(CallListClientResponse.from(client, debtInfo, null, null));
        }

        sortByDebt(result);
        return result;
    }

    @Transactional
    public CallListSummaryResponse generateCallList(WorklistFilterRequest request, User admin)
    {
        List<Client> clients = fetchFilteredClients(request);

        callListItemRepository.deleteAllFast();

        if (clients.isEmpty())
        {
            return emptyCallListSummary();
        }

        List<Long> clientIds = clients.stream().map(Client::getId).toList();
        Map<Long, List<DocumentTransferSnapshot>> snapshotsByClientId = loadSnapshotsByClientId(clientIds);

        List<Client> filtered = new ArrayList<>();
        Map<Long, ClientDebtInfo> debtInfoByClientId = new LinkedHashMap<>();
        for (Client client : clients)
        {
            List<DocumentTransferSnapshot> snapshots =
                    snapshotsByClientId.getOrDefault(client.getId(), Collections.emptyList());
            ClientDebtInfo debtInfo = dynamicsService.getClientDebtInfo(client, snapshots);

            if (matchesDebtFilters(request, debtInfo))
            {
                filtered.add(client);
                debtInfoByClientId.put(client.getId(), debtInfo);
            }
        }

        if (filtered.isEmpty())
        {
            return emptyCallListSummary();
        }

        filtered.sort(Comparator
                .comparingInt((Client c) -> -debtInfoByClientId.get(c.getId()).notTransferredDocuments())
                .thenComparingDouble(c ->
                {
                    Double pct = debtInfoByClientId.get(c.getId()).percentNotTransferred();
                    return pct == null ? Double.MIN_VALUE : -pct;
                }));

        Instant now = Instant.now();
        List<CallListItem> itemsToSave = new ArrayList<>(filtered.size());
        List<CallListClientResponse> responseClients = new ArrayList<>(filtered.size());

        for (int i = 0; i < filtered.size(); i++)
        {
            Client client = filtered.get(i);
            ClientDebtInfo debtInfo = debtInfoByClientId.get(client.getId());

            if (client.getStage() == WorkStage.NOT_IN_WORK)
            {
                client.setStage(WorkStage.IN_POOL);
                clientRepository.save(client);
                eventService.record(client, admin, ClientEventType.STAGE_CHANGED,
                        "Included in call list (common pool)");
            }

            itemsToSave.add(CallListItem.builder()
                    .client(client)
                    .createdBy(admin)
                    .createdAt(now)
                    .position(i)
                    .build());

            responseClients.add(CallListClientResponse.from(client, debtInfo, now, admin.getUsername()));
        }

        callListItemRepository.saveAll(itemsToSave);

        return CallListSummaryResponse.builder()
                .totalClients(responseClients.size())
                .updatedAt(now)
                .updatedByUsername(admin.getUsername())
                .clients(responseClients)
                .build();
    }

    @Transactional(readOnly = true)
    public CallListSummaryResponse getCallList()
    {
        List<CallListItem> items = callListItemRepository.findAllWithClientsOrderByPositionAsc();
        if (items.isEmpty())
        {
            return emptyCallListSummary();
        }

        List<Long> clientIds = items.stream().map(item -> item.getClient().getId()).toList();
        Map<Long, List<DocumentTransferSnapshot>> snapshotsByClientId = loadSnapshotsByClientId(clientIds);

        List<CallListClientResponse> responseClients = new ArrayList<>(items.size());
        for (CallListItem item : items)
        {
            Client client = item.getClient();
            List<DocumentTransferSnapshot> snapshots =
                    snapshotsByClientId.getOrDefault(client.getId(), Collections.emptyList());
            ClientDebtInfo debtInfo = dynamicsService.getClientDebtInfo(client, snapshots);

            responseClients.add(CallListClientResponse.from(
                    client, debtInfo, item.getCreatedAt(), item.getCreatedBy().getUsername()));
        }

        CallListItem first = items.get(0);
        return CallListSummaryResponse.builder()
                .totalClients(responseClients.size())
                .updatedAt(first.getCreatedAt())
                .updatedByUsername(first.getCreatedBy().getUsername())
                .clients(responseClients)
                .build();
    }

    @Transactional
    public void clearCallList(User admin)
    {
        callListItemRepository.deleteAllFast();
    }

    private List<Client> fetchFilteredClients(WorklistFilterRequest request)
    {
        Specification<Client> spec = (root, query, cb) -> cb.conjunction();

        if (request.clientType() != null)
        {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("clientType"), request.clientType()));
        }
        if (request.search() != null && !request.search().isBlank())
        {
            String pattern = "%" + request.search().trim().toLowerCase(Locale.ROOT) + "%";
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("kcrCode")), pattern),
                    cb.like(cb.lower(root.get("inn")), pattern),
                    cb.like(cb.lower(root.get("organizationName")), pattern)
            ));
        }
        if (request.clientIds() != null && !request.clientIds().isEmpty())
        {
            spec = spec.and((root, query, cb) -> root.get("id").in(request.clientIds()));
        }

        return clientRepository.findAll(spec);
    }

    private Map<Long, List<DocumentTransferSnapshot>> loadSnapshotsByClientId(Collection<Long> clientIds)
    {
        return snapshotRepository.findByClientIdIn(clientIds).stream()
                .filter(s -> s.getClient() != null)
                .collect(Collectors.groupingBy(s -> s.getClient().getId()));
    }

    private boolean matchesDebtFilters(WorklistFilterRequest request, ClientDebtInfo debtInfo)
    {
        if (Boolean.TRUE.equals(request.hasDebt()) && debtInfo.notTransferredDocuments() <= 0)
        {
            return false;
        }
        if (request.minDebt() != null && debtInfo.notTransferredDocuments() < request.minDebt())
        {
            return false;
        }
        if (request.minUntransferredPercent() != null)
        {
            boolean isNewcomerRisk = debtInfo.dynamicsCategory() == DynamicsCategory.NEWCOMER_RISK;
            Double percent = debtInfo.percentNotTransferred();
            if (!isNewcomerRisk && (percent == null || percent < request.minUntransferredPercent()))
            {
                return false;
            }
        }
        if (request.maxUntransferredPercent() != null)
        {
            Double percent = debtInfo.percentNotTransferred();
            if (percent != null && percent > request.maxUntransferredPercent())
            {
                return false;
            }
        }
        if (Boolean.TRUE.equals(request.lowDynamics()))
        {
            DynamicsCategory cat = debtInfo.dynamicsCategory();
            if (cat != DynamicsCategory.UNCHANGED_OR_GROWING && cat != DynamicsCategory.PERSISTENT)
            {
                return false;
            }
        }
        if (request.dynamicsCategory() != null && debtInfo.dynamicsCategory() != request.dynamicsCategory())
        {
            return false;
        }
        return true;
    }

    private static void sortByDebt(List<CallListClientResponse> list)
    {
        list.sort(Comparator.comparing(CallListClientResponse::notTransferredDocuments, Comparator.reverseOrder())
                .thenComparing(CallListClientResponse::percentNotTransferred,
                        Comparator.nullsLast(Comparator.reverseOrder())));
    }

    private static CallListSummaryResponse emptyCallListSummary()
    {
        return CallListSummaryResponse.builder()
                .totalClients(0)
                .updatedAt(null)
                .updatedByUsername(null)
                .clients(Collections.emptyList())
                .build();
    }
}

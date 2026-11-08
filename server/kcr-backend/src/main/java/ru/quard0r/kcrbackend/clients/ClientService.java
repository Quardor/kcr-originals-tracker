package ru.quard0r.kcrbackend.clients;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.quard0r.kcrbackend.clients.dto.AssignmentUpdateRequest;
import ru.quard0r.kcrbackend.clients.dto.ClientCreateRequest;
import ru.quard0r.kcrbackend.clients.dto.ClientResponse;
import ru.quard0r.kcrbackend.clients.dto.ClientUpdateRequest;
import ru.quard0r.kcrbackend.clients.dto.StageUpdateRequest;
import ru.quard0r.kcrbackend.clients.events.ClientEventService;
import ru.quard0r.kcrbackend.clients.events.ClientEventType;
import ru.quard0r.kcrbackend.clients.model.Client;
import ru.quard0r.kcrbackend.clients.model.ClientType;
import ru.quard0r.kcrbackend.clients.model.WorkStage;
import ru.quard0r.kcrbackend.clients.repository.ClientRepository;
import ru.quard0r.kcrbackend.common.PageResponse;
import ru.quard0r.kcrbackend.dto.response.SnapshotResponse;
import ru.quard0r.kcrbackend.exception.DomainException;
import ru.quard0r.kcrbackend.exception.ErrorCode;
import ru.quard0r.kcrbackend.model.DocumentTransferSnapshot;
import ru.quard0r.kcrbackend.model.Role;
import ru.quard0r.kcrbackend.model.User;
import ru.quard0r.kcrbackend.repository.DocumentTransferSnapshotRepository;
import ru.quard0r.kcrbackend.repository.UserRepository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Карточки клиентов и операторский цикл над ними (§11.4): фильтрация списка,
 * атомарное взятие свободного клиента, переназначение ответственного, смена этапа
 * и список «сегодня нужно». Каждая мутация оставляет системное событие в истории
 * клиента (ClientEventService, §11.2).
 */
@Service
@RequiredArgsConstructor
public class ClientService {

    private final ClientRepository clientRepository;
    private final DocumentTransferSnapshotRepository snapshotRepository;
    private final UserRepository userRepository;
    private final ClientEventService eventService;

    @Transactional(readOnly = true)
    public PageResponse<ClientResponse> findClients(String search, ClientType clientType, Boolean kcrActive,
                                                    Boolean kcrBlocked, String flag, WorkStage stage,
                                                    Long assigneeId, Boolean unassigned, Pageable pageable) {
        Specification<Client> specification = Specification.allOf(
                matchSearch(search),
                matchEquals("clientType", clientType),
                matchEquals("kcrActive", kcrActive),
                matchEquals("kcrBlocked", kcrBlocked),
                matchFlag(flag),
                matchEquals("stage", stage),
                matchAssignee(assigneeId, unassigned));

        Page<ClientResponse> page = clientRepository.findAll(specification, pageable)
                .map(ClientResponse::from);
        return PageResponse.from(page);
    }

    /** «Сегодня нужно» (§11.4): срок следующего контакта уже наступил; оператору —
     *  свои клиенты, админу — все. */
    @Transactional(readOnly = true)
    public PageResponse<ClientResponse> findDueToday(User actor, Pageable pageable) {
        List<Specification<Client>> specifications = new ArrayList<>();
        specifications.add((root, query, cb) ->
                cb.lessThanOrEqualTo(root.get("nextContactAt"), LocalDate.now()));
        if (actor.getRole() == Role.OPERATOR) {
            specifications.add((root, query, cb) ->
                    cb.equal(root.get("assignee").get("id"), actor.getId()));
        }

        Page<ClientResponse> page = clientRepository.findAll(Specification.allOf(specifications), pageable)
                .map(ClientResponse::from);
        return PageResponse.from(page);
    }

    @Transactional(readOnly = true)
    public ClientResponse getClient(Long id) {
        return ClientResponse.from(getExistingClient(id));
    }

    @Transactional(readOnly = true)
    public List<SnapshotResponse> getSnapshots(Long clientId, String reportingPeriod) {
        if (!clientRepository.existsById(clientId)) {
            throw new DomainException(ErrorCode.CLIENT_NOT_FOUND);
        }
        List<DocumentTransferSnapshot> snapshots = isNotBlank(reportingPeriod)
                ? snapshotRepository.findByClientIdAndReportingPeriodOrderBySnapshotDateAscIdAsc(
                        clientId, reportingPeriod.trim())
                : snapshotRepository.findByClientIdOrderBySnapshotDateAscIdAsc(clientId);
        return snapshots.stream()
                .map(snapshot -> SnapshotResponse.from(snapshot, clientId))
                .toList();
    }

    @Transactional
    public ClientResponse create(ClientCreateRequest request) {
        if (clientRepository.existsByKcrCode(request.kcrCode())) {
            throw new DomainException(ErrorCode.CLIENT_ALREADY_EXISTS);
        }
        Client client = Client.builder()
                .kcrCode(request.kcrCode())
                .inn(request.inn())
                .kpp(request.kpp())
                .organizationName(request.organizationName())
                .internalId(request.internalId())
                .clientType(request.clientType())
                .kcrActive(request.kcrActive())
                .kcrBlocked(request.kcrBlocked())
                .tariff(request.tariff())
                .ekbTravelTime(request.ekbTravelTime())
                .manager(request.manager())
                .flags(request.flags())
                .contactPerson(request.contactPerson())
                .contactPhone(request.contactPhone())
                .contactEmail(request.contactEmail())
                .build();
        return ClientResponse.from(clientRepository.save(client));
    }

    @Transactional
    public ClientResponse update(Long id, ClientUpdateRequest request) {
        Client client = getExistingClient(id);

        if (request.inn() != null) {
            client.setInn(request.inn());
        }
        if (request.kpp() != null) {
            client.setKpp(request.kpp());
        }
        if (request.organizationName() != null) {
            if (request.organizationName().isBlank()) {
                throw new DomainException(ErrorCode.BLANK_ORGANIZATION_NAME);
            }
            client.setOrganizationName(request.organizationName());
        }
        if (request.internalId() != null) {
            client.setInternalId(request.internalId());
        }
        if (request.clientType() != null) {
            client.setClientType(request.clientType());
        }
        if (request.kcrActive() != null) {
            client.setKcrActive(request.kcrActive());
        }
        if (request.kcrBlocked() != null) {
            client.setKcrBlocked(request.kcrBlocked());
        }
        if (request.tariff() != null) {
            client.setTariff(request.tariff());
        }
        if (request.ekbTravelTime() != null) {
            client.setEkbTravelTime(request.ekbTravelTime());
        }
        if (request.manager() != null) {
            client.setManager(request.manager());
        }
        if (request.flags() != null) {
            client.setFlags(request.flags());
        }
        if (request.contactPerson() != null) {
            client.setContactPerson(request.contactPerson());
        }
        if (request.contactPhone() != null) {
            client.setContactPhone(request.contactPhone());
        }
        if (request.contactEmail() != null) {
            client.setContactEmail(request.contactEmail());
        }

        return ClientResponse.from(clientRepository.save(client));
    }

    /**
     * Атомарное взятие свободного клиента (§11.2): условный UPDATE исключает
     * одновременное занятие двумя операторами; повторное взятие (в том числе
     * текущим ответственным) — конфликт.
     */
    @Transactional
    public ClientResponse take(Long clientId, User operator) {
        int updated = clientRepository.assignAssigneeIfFree(clientId, operator.getId());
        if (updated == 0) {
            if (!clientRepository.existsById(clientId)) {
                throw new DomainException(ErrorCode.CLIENT_NOT_FOUND);
            }
            throw new DomainException(ErrorCode.CLIENT_ALREADY_TAKEN);
        }
        Client client = getExistingClient(clientId);
        eventService.record(client, operator, ClientEventType.ASSIGNEE_CHANGED,
                "Взят в работу: %s".formatted(operator.getUsername()));
        return ClientResponse.from(client);
    }

    /** Переназначение/освобождение ответственного (§11.4, админ): assigneeId = null
     *  освобождает клиента. */
    @Transactional
    public ClientResponse updateAssignment(Long clientId, AssignmentUpdateRequest request, User admin) {
        Client client = getExistingClient(clientId);
        User target = null;
        if (request.assigneeId() != null) {
            target = userRepository.findById(request.assigneeId())
                    .orElseThrow(() -> new DomainException(ErrorCode.USER_NOT_FOUND));
        }
        Long currentId = client.getAssignee() == null ? null : client.getAssignee().getId();
        Long targetId = target == null ? null : target.getId();
        if (Objects.equals(currentId, targetId)) {
            return ClientResponse.from(client);
        }
        String previous = client.getAssignee() == null ? "—"
                : client.getAssignee().getUsername();
        String next = target == null ? "освобождён" : target.getUsername();
        client.setAssignee(target);
        eventService.record(client, admin, ClientEventType.ASSIGNEE_CHANGED,
                "Ответственный: %s → %s".formatted(previous, next));
        return ClientResponse.from(clientRepository.save(client));
    }

    /** Смена этапа (§11.4): правила — StagePolicy; повторная установка того же
     *  этапа идемпотентна и событие не создаёт. */
    @Transactional
    public ClientResponse updateStage(Long clientId, StageUpdateRequest request, User actor) {
        Client client = getExistingClient(clientId);
        if (client.getStage() == request.stage()) {
            return ClientResponse.from(client);
        }
        StagePolicy.assertChangeAllowed(actor, client, request.stage());
        WorkStage previous = client.getStage();
        client.setStage(request.stage());
        String comment = "Этап: %s → %s".formatted(previous, request.stage());
        if (isNotBlank(request.comment())) {
            comment = comment + ". " + request.comment().strip();
        }
        eventService.record(client, actor, ClientEventType.STAGE_CHANGED, comment);
        return ClientResponse.from(clientRepository.save(client));
    }

    private Client getExistingClient(Long id) {
        return clientRepository.findById(id)
                .orElseThrow(() -> new DomainException(ErrorCode.CLIENT_NOT_FOUND));
    }

    private Specification<Client> matchSearch(String search) {
        return (root, query, cb) -> {
            if (!isNotBlank(search)) {
                return cb.conjunction();
            }
            String pattern = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
            return cb.or(
                    cb.like(cb.lower(root.get("kcrCode")), pattern),
                    cb.like(cb.lower(root.get("inn")), pattern));
        };
    }

    private Specification<Client> matchEquals(String field, Object value) {
        return (root, query, cb) -> value == null ? cb.conjunction() : cb.equal(root.get(field), value);
    }

    private Specification<Client> matchAssignee(Long assigneeId, Boolean unassigned) {
        return (root, query, cb) -> {
            if (assigneeId != null) {
                return cb.equal(root.get("assignee").get("id"), assigneeId);
            }
            if (Boolean.TRUE.equals(unassigned)) {
                return cb.isNull(root.get("assignee"));
            }
            return cb.conjunction();
        };
    }

    private Specification<Client> matchFlag(String flag) {
        return (root, query, cb) -> {
            if (!isNotBlank(flag)) {
                return cb.conjunction();
            }
            return cb.isTrue(cb.function("array_contains", Boolean.class,
                    root.get("flags"), cb.literal(flag.trim())));
        };
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }
}

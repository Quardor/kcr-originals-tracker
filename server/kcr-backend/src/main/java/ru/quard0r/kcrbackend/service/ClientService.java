package ru.quard0r.kcrbackend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.quard0r.kcrbackend.dto.request.ClientCreateRequest;
import ru.quard0r.kcrbackend.dto.request.ClientUpdateRequest;
import ru.quard0r.kcrbackend.dto.response.ClientResponse;
import ru.quard0r.kcrbackend.dto.response.PageResponse;
import ru.quard0r.kcrbackend.dto.response.SnapshotResponse;
import ru.quard0r.kcrbackend.exception.DomainException;
import ru.quard0r.kcrbackend.exception.ErrorCode;
import ru.quard0r.kcrbackend.model.Client;
import ru.quard0r.kcrbackend.model.ClientType;
import ru.quard0r.kcrbackend.model.DocumentTransferSnapshot;
import ru.quard0r.kcrbackend.repository.ClientRepository;
import ru.quard0r.kcrbackend.repository.DocumentTransferSnapshotRepository;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class ClientService {

    private final ClientRepository clientRepository;
    private final DocumentTransferSnapshotRepository snapshotRepository;

    public PageResponse<ClientResponse> findClients(String search, ClientType clientType, Boolean kcrActive,
                                                    Boolean kcrBlocked, String flag, Pageable pageable) {
        Specification<Client> specification = Specification.allOf(
                matchSearch(search),
                matchEquals("clientType", clientType),
                matchEquals("kcrActive", kcrActive),
                matchEquals("kcrBlocked", kcrBlocked),
                matchFlag(flag));

        Page<ClientResponse> page = clientRepository.findAll(specification, pageable)
                .map(ClientResponse::from);
        return PageResponse.from(page);
    }

    public ClientResponse getClient(Long id) {
        return ClientResponse.from(getExistingClient(id));
    }

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

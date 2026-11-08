package ru.quard0r.kcrbackend.clients.events;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.quard0r.kcrbackend.clients.events.ClaimStatus;
import ru.quard0r.kcrbackend.clients.events.dto.ClaimCreateRequest;
import ru.quard0r.kcrbackend.clients.events.dto.ClaimResponse;
import ru.quard0r.kcrbackend.clients.events.dto.ClaimUpdateRequest;
import ru.quard0r.kcrbackend.clients.events.dto.SanctionCreateRequest;
import ru.quard0r.kcrbackend.clients.events.dto.SanctionResponse;
import ru.quard0r.kcrbackend.clients.events.dto.SanctionUpdateRequest;
import ru.quard0r.kcrbackend.clients.model.Client;
import ru.quard0r.kcrbackend.clients.repository.ClientRepository;
import ru.quard0r.kcrbackend.common.error.DomainException;
import ru.quard0r.kcrbackend.common.error.ErrorCode;
import ru.quard0r.kcrbackend.model.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Претензионная работа (§11.4): претензии и санкции добавляет и меняет
 * администратор; каждая мутация дублируется событием CLAIM/SANCTION в общей
 * истории клиента. Изменения полей описываются в комментарии события по-русски
 * (данные истории читает оператор в интерфейсе).
 */
@Service
@RequiredArgsConstructor
public class ClaimService {

    private final ClaimRepository claimRepository;
    private final SanctionRepository sanctionRepository;
    private final ClientRepository clientRepository;
    private final ClientEventService eventService;

    @Transactional(readOnly = true)
    public List<ClaimResponse> getByClient(Long clientId) {
        if (!clientRepository.existsById(clientId)) {
            throw new DomainException(ErrorCode.CLIENT_NOT_FOUND);
        }
        List<Claim> claims = claimRepository.findByClientIdOrderByIdAsc(clientId);
        Map<Long, List<Sanction>> sanctionsByClaim = loadSanctions(claims);
        return claims.stream()
                .map(claim -> ClaimResponse.from(claim, sanctionsByClaim
                        .getOrDefault(claim.getId(), List.of())
                        .stream()
                        .map(SanctionResponse::from)
                        .toList()))
                .toList();
    }

    @Transactional
    public ClaimResponse create(Long clientId, ClaimCreateRequest request, User admin) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new DomainException(ErrorCode.CLIENT_NOT_FOUND));
        Claim claim = claimRepository.save(Claim.builder()
                .client(client)
                .number(request.number())
                .claimDate(request.claimDate())
                .incidentNumber(request.incidentNumber())
                .receivedByClientAt(request.receivedByClientAt())
                .status(request.status() == null ? ClaimStatus.NEW : request.status())
                .comment(request.comment())
                .build());
        eventService.record(client, admin, ClientEventType.CLAIM,
                "Претензия №%s зарегистрирована (статус %s)"
                        .formatted(claim.getNumber(), claim.getStatus()));
        return toResponse(claim, List.of());
    }

    @Transactional
    public ClaimResponse update(Long claimId, ClaimUpdateRequest request, User admin) {
        Claim claim = getClaim(claimId);
        List<String> changes = new ArrayList<>();
        if (request.status() != null && request.status() != claim.getStatus()) {
            changes.add("статус %s → %s".formatted(claim.getStatus(), request.status()));
            claim.setStatus(request.status());
        }
        if (request.receivedByClientAt() != null
                && !request.receivedByClientAt().equals(claim.getReceivedByClientAt())) {
            changes.add("получена клиентом %s".formatted(request.receivedByClientAt()));
            claim.setReceivedByClientAt(request.receivedByClientAt());
        }
        if (request.incidentNumber() != null && !request.incidentNumber().equals(claim.getIncidentNumber())) {
            changes.add("инцидент №%s".formatted(request.incidentNumber()));
            claim.setIncidentNumber(request.incidentNumber());
        }
        if (request.comment() != null && !request.comment().equals(claim.getComment())) {
            claim.setComment(request.comment());
        }
        if (!changes.isEmpty()) {
            eventService.record(claim.getClient(), admin, ClientEventType.CLAIM,
                    "Претензия №%s: %s".formatted(claim.getNumber(), String.join(", ", changes)));
        }
        return toResponse(claim, sanctionsOf(claim));
    }

    @Transactional
    public ClaimResponse addSanction(Long claimId, SanctionCreateRequest request, User admin) {
        Claim claim = getClaim(claimId);
        Sanction sanction = sanctionRepository.save(Sanction.builder()
                .claim(claim)
                .name(request.name())
                .status(request.status() == null ? SanctionStatus.PLANNED : request.status())
                .build());
        eventService.record(claim.getClient(), admin, ClientEventType.SANCTION,
                "Санкция «%s» по претензии №%s: %s"
                        .formatted(sanction.getName(), claim.getNumber(), sanction.getStatus()));
        return toResponse(claim, sanctionsOf(claim));
    }

    @Transactional
    public ClaimResponse updateSanction(Long sanctionId, SanctionUpdateRequest request, User admin) {
        Sanction sanction = sanctionRepository.findById(sanctionId)
                .orElseThrow(() -> new DomainException(ErrorCode.SANCTION_NOT_FOUND));
        List<String> changes = new ArrayList<>();
        if (request.status() != null && request.status() != sanction.getStatus()) {
            changes.add("статус %s → %s".formatted(sanction.getStatus(), request.status()));
            sanction.setStatus(request.status());
        }
        if (request.name() != null && !request.name().equals(sanction.getName())) {
            changes.add("название «%s»".formatted(request.name()));
            sanction.setName(request.name());
        }
        Claim claim = sanction.getClaim();
        if (!changes.isEmpty()) {
            eventService.record(claim.getClient(), admin, ClientEventType.SANCTION,
                    "Санкция «%s» по претензии №%s: %s"
                            .formatted(sanction.getName(), claim.getNumber(), String.join(", ", changes)));
        }
        return toResponse(claim, sanctionsOf(claim));
    }

    private Map<Long, List<Sanction>> loadSanctions(List<Claim> claims) {
        if (claims.isEmpty()) {
            return Map.of();
        }
        return sanctionRepository
                .findByClaimIdInOrderByIdAsc(claims.stream().map(Claim::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(sanction -> sanction.getClaim().getId()));
    }

    private List<SanctionResponse> sanctionsOf(Claim claim) {
        return sanctionRepository.findByClaimIdInOrderByIdAsc(List.of(claim.getId()))
                .stream()
                .map(SanctionResponse::from)
                .toList();
    }

    private ClaimResponse toResponse(Claim claim, List<SanctionResponse> sanctions) {
        return ClaimResponse.from(claim, sanctions);
    }

    private Claim getClaim(Long claimId) {
        return claimRepository.findById(claimId)
                .orElseThrow(() -> new DomainException(ErrorCode.CLAIM_NOT_FOUND));
    }
}

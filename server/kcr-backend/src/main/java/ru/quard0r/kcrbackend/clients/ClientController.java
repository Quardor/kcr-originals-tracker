package ru.quard0r.kcrbackend.clients;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.quard0r.kcrbackend.clients.dto.AssignmentUpdateRequest;
import ru.quard0r.kcrbackend.clients.dto.ClientCreateRequest;
import ru.quard0r.kcrbackend.clients.dto.ClientResponse;
import ru.quard0r.kcrbackend.clients.dto.ClientUpdateRequest;
import ru.quard0r.kcrbackend.clients.dto.StageUpdateRequest;
import ru.quard0r.kcrbackend.clients.model.ClientType;
import ru.quard0r.kcrbackend.clients.model.WorkStage;
import ru.quard0r.kcrbackend.common.PageLimits;
import ru.quard0r.kcrbackend.common.PageResponse;
import ru.quard0r.kcrbackend.dto.response.SnapshotResponse;
import ru.quard0r.kcrbackend.exception.DomainException;
import ru.quard0r.kcrbackend.exception.ErrorCode;
import ru.quard0r.kcrbackend.security.CustomUserDetails;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/clients")
@RequiredArgsConstructor
public class ClientController {

    private static final List<String> SORT_FIELDS =
            List.of("id", "kcrCode", "inn", "organizationName", "stage", "nextContactAt");

    private final ClientService clientService;

    @GetMapping
    public ResponseEntity<PageResponse<ClientResponse>> findClients(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) ClientType clientType,
            @RequestParam(required = false) Boolean kcrActive,
            @RequestParam(required = false) Boolean kcrBlocked,
            @RequestParam(required = false) String flag,
            @RequestParam(required = false) WorkStage stage,
            @RequestParam(required = false) Long assigneeId,
            @RequestParam(required = false) Boolean unassigned,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "page must be >= 0") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size must be >= 1")
            @Max(value = PageLimits.MAX_PAGE_SIZE, message = "size must be <= " + PageLimits.MAX_PAGE_SIZE)
            int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir)
    {
        if (!SORT_FIELDS.contains(sortBy)) {
            throw new DomainException(ErrorCode.INVALID_SORT_FIELD);
        }
        Sort.Direction direction = parseDirection(sortDir);
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(direction, sortBy));
        return ResponseEntity.ok(clientService.findClients(search, clientType, kcrActive, kcrBlocked,
                flag, stage, assigneeId, unassigned, pageRequest));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ClientResponse> getClient(@PathVariable Long id)
    {
        return ResponseEntity.ok(clientService.getClient(id));
    }

    @GetMapping("/{id}/snapshots")
    public ResponseEntity<List<SnapshotResponse>> getSnapshots(
            @PathVariable Long id,
            @RequestParam(required = false) String reportingPeriod)
    {
        return ResponseEntity.ok(clientService.getSnapshots(id, reportingPeriod));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ClientResponse> createClient(@Valid @RequestBody ClientCreateRequest request)
    {
        return ResponseEntity.status(HttpStatus.CREATED).body(clientService.create(request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ClientResponse> updateClient(
            @PathVariable Long id,
            @Valid @RequestBody ClientUpdateRequest request)
    {
        return ResponseEntity.ok(clientService.update(id, request));
    }

    /** Атомарно взять свободного клиента (§11.4); админ наследует возможность оператора. */
    @PostMapping("/{id}/take")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<ClientResponse> takeClient(
            @PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails)
    {
        return ResponseEntity.ok(clientService.take(id, userDetails.user()));
    }

    /** Переназначить/освободить ответственного (§11.4, админ). */
    @PatchMapping("/{id}/assignment")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ClientResponse> updateAssignment(
            @PathVariable Long id,
            @Valid @RequestBody AssignmentUpdateRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails)
    {
        return ResponseEntity.ok(clientService.updateAssignment(id, request, userDetails.user()));
    }

    /** Сменить этап (§11.4): админ — любой, оператор — по правилам StagePolicy. */
    @PatchMapping("/{id}/stage")
    public ResponseEntity<ClientResponse> updateStage(
            @PathVariable Long id,
            @Valid @RequestBody StageUpdateRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails)
    {
        return ResponseEntity.ok(clientService.updateStage(id, request, userDetails.user()));
    }

    private Sort.Direction parseDirection(String sortDir) {
        return switch (sortDir.toLowerCase(Locale.ROOT)) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw new DomainException(ErrorCode.INVALID_SORT_DIRECTION);
        };
    }
}

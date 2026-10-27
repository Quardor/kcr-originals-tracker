package ru.quard0r.kcrbackend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.quard0r.kcrbackend.dto.request.ClientCreateRequest;
import ru.quard0r.kcrbackend.dto.request.ClientUpdateRequest;
import ru.quard0r.kcrbackend.dto.response.ClientResponse;
import ru.quard0r.kcrbackend.dto.response.PageResponse;
import ru.quard0r.kcrbackend.dto.response.SnapshotResponse;
import ru.quard0r.kcrbackend.exception.base.BadRequestException;
import ru.quard0r.kcrbackend.model.ClientType;
import ru.quard0r.kcrbackend.service.ClientService;

import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/v1/clients")
@RequiredArgsConstructor
public class ClientController {

    private static final List<String> SORT_FIELDS = List.of("id", "kcrCode", "inn", "organizationName");

    private final ClientService clientService;

    @GetMapping
    public ResponseEntity<PageResponse<ClientResponse>> findClients(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) ClientType clientType,
            @RequestParam(required = false) Boolean kcrActive,
            @RequestParam(required = false) Boolean kcrBlocked,
            @RequestParam(required = false) String flag,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir)
    {
        if (!SORT_FIELDS.contains(sortBy)) {
            throw new BadRequestException("Invalid sort field");
        }
        Sort.Direction direction = parseDirection(sortDir);
        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(direction, sortBy));
        return ResponseEntity.ok(
                clientService.findClients(search, clientType, kcrActive, kcrBlocked, flag, pageRequest));
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

    private Sort.Direction parseDirection(String sortDir) {
        return switch (sortDir.toLowerCase(Locale.ROOT)) {
            case "asc" -> Sort.Direction.ASC;
            case "desc" -> Sort.Direction.DESC;
            default -> throw new BadRequestException("Invalid sort direction");
        };
    }
}

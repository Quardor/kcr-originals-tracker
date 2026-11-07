package ru.quard0r.kcrbackend.clients.events;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.quard0r.kcrbackend.clients.events.dto.ClaimCreateRequest;
import ru.quard0r.kcrbackend.clients.events.dto.ClaimResponse;
import ru.quard0r.kcrbackend.clients.events.dto.ClaimUpdateRequest;
import ru.quard0r.kcrbackend.clients.events.dto.SanctionCreateRequest;
import ru.quard0r.kcrbackend.clients.events.dto.SanctionUpdateRequest;
import ru.quard0r.kcrbackend.security.CustomUserDetails;

import java.util.List;

/**
 * Претензии и санкции (§11.4): мутации — админ, читает карточку — любая роль.
 * Мутации дублируются событиями CLAIM/SANCTION в истории клиента.
 */
@RestController
@RequiredArgsConstructor
public class ClaimController {

    private final ClaimService claimService;

    @PostMapping("/api/v1/clients/{id}/claims")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ClaimResponse> createClaim(
            @PathVariable Long id,
            @Valid @RequestBody ClaimCreateRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails)
    {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(claimService.create(id, request, userDetails.user()));
    }

    @GetMapping("/api/v1/clients/{id}/claims")
    public ResponseEntity<List<ClaimResponse>> getClientClaims(@PathVariable Long id)
    {
        return ResponseEntity.ok(claimService.getByClient(id));
    }

    @PatchMapping("/api/v1/claims/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ClaimResponse> updateClaim(
            @PathVariable Long id,
            @Valid @RequestBody ClaimUpdateRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails)
    {
        return ResponseEntity.ok(claimService.update(id, request, userDetails.user()));
    }

    @PostMapping("/api/v1/claims/{id}/sanctions")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ClaimResponse> addSanction(
            @PathVariable Long id,
            @Valid @RequestBody SanctionCreateRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails)
    {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(claimService.addSanction(id, request, userDetails.user()));
    }

    @PatchMapping("/api/v1/sanctions/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ClaimResponse> updateSanction(
            @PathVariable Long id,
            @Valid @RequestBody SanctionUpdateRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails)
    {
        return ResponseEntity.ok(claimService.updateSanction(id, request, userDetails.user()));
    }
}

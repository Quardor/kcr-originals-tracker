package ru.quard0r.kcrbackend.clients.events;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.quard0r.kcrbackend.clients.events.dto.ClaimCreateRequest;
import ru.quard0r.kcrbackend.clients.events.dto.ClaimResponse;
import ru.quard0r.kcrbackend.clients.events.dto.ClaimUpdateRequest;
import ru.quard0r.kcrbackend.clients.events.dto.SanctionCreateRequest;
import ru.quard0r.kcrbackend.clients.events.dto.SanctionResponse;
import ru.quard0r.kcrbackend.clients.events.dto.SanctionUpdateRequest;
import ru.quard0r.kcrbackend.clients.model.Client;
import ru.quard0r.kcrbackend.clients.repository.ClientRepository;
import ru.quard0r.kcrbackend.exception.DomainException;
import ru.quard0r.kcrbackend.exception.ErrorCode;
import ru.quard0r.kcrbackend.model.Role;
import ru.quard0r.kcrbackend.model.User;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClaimServiceTest {

    @Mock
    private ClaimRepository claimRepository;

    @Mock
    private SanctionRepository sanctionRepository;

    @Mock
    private ClientRepository clientRepository;

    @Mock
    private ClientEventService eventService;

    @InjectMocks
    private ClaimService claimService;

    private final User admin = User.builder()
            .id(1L).username("admin").password("password123").role(Role.ADMIN).build();

    private Client client() {
        return Client.builder()
                .id(1L).kcrCode("ABC12345").inn("123456789012")
                .organizationName("Organization").build();
    }

    private Claim claim() {
        return Claim.builder()
                .id(5L).client(client()).number("ПР-1")
                .claimDate(LocalDate.of(2026, 11, 1)).status(ClaimStatus.NEW)
                .build();
    }

    @Test
    void createRegistersClaimWithDefaultStatusAndEvent() {
        Client client = client();
        when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
        when(claimRepository.save(any(Claim.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClaimResponse response = claimService.create(1L, new ClaimCreateRequest(
                "ПР-1", LocalDate.of(2026, 11, 1), "INC-7", null, null, "долг"), admin);

        assertEquals(ClaimStatus.NEW, response.status());
        assertEquals("ПР-1", response.number());
        verify(eventService).record(client, admin, ClientEventType.CLAIM,
                "Претензия №ПР-1 зарегистрирована (статус NEW)");
    }

    @Test
    void updateRecordsStatusAndReceivedChanges() {
        Claim claim = claim();
        when(claimRepository.findById(5L)).thenReturn(Optional.of(claim));

        claimService.update(5L, new ClaimUpdateRequest(
                null, LocalDate.of(2026, 11, 5), ClaimStatus.RECEIVED, null), admin);

        assertEquals(ClaimStatus.RECEIVED, claim.getStatus());
        assertEquals(LocalDate.of(2026, 11, 5), claim.getReceivedByClientAt());
        verify(eventService).record(claim.getClient(), admin, ClientEventType.CLAIM,
                "Претензия №ПР-1: статус NEW → RECEIVED, получена клиентом 2026-11-05");
    }

    @Test
    void updateWithoutChangesRecordsNoEvent() {
        Claim claim = claim();
        when(claimRepository.findById(5L)).thenReturn(Optional.of(claim));

        claimService.update(5L, new ClaimUpdateRequest(null, null, null, "комментарий"), admin);

        assertEquals("комментарий", claim.getComment());
        verify(eventService, never()).record(any(), any(), any(), any());
    }

    @Test
    void updateThrowsForUnknownClaim() {
        when(claimRepository.findById(42L)).thenReturn(Optional.empty());

        DomainException exception = assertThrows(DomainException.class, () -> claimService.update(42L,
                new ClaimUpdateRequest(null, null, ClaimStatus.SENT, null), admin));
        assertEquals(ErrorCode.CLAIM_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void addSanctionDefaultsToPlannedAndRecordsEvent() {
        Claim claim = claim();
        when(claimRepository.findById(5L)).thenReturn(Optional.of(claim));
        when(sanctionRepository.save(any(Sanction.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(sanctionRepository.findByClaimIdInOrderByIdAsc(List.of(5L))).thenReturn(List.of(
                Sanction.builder().id(9L).claim(claim).name("приостановка")
                        .status(SanctionStatus.PLANNED).build()));

        ClaimResponse response = claimService.addSanction(5L,
                new SanctionCreateRequest("приостановка", null), admin);

        assertEquals(1, response.sanctions().size());
        assertEquals(SanctionStatus.PLANNED, response.sanctions().get(0).status());
        verify(eventService).record(claim.getClient(), admin, ClientEventType.SANCTION,
                "Санкция «приостановка» по претензии №ПР-1: PLANNED");
    }

    @Test
    void updateSanctionRecordsStatusChange() {
        Sanction sanction = Sanction.builder()
                .id(9L).claim(claim()).name("приостановка").status(SanctionStatus.PLANNED).build();
        when(sanctionRepository.findById(9L)).thenReturn(Optional.of(sanction));
        when(sanctionRepository.findByClaimIdInOrderByIdAsc(List.of(5L))).thenReturn(List.of(sanction));

        claimService.updateSanction(9L, new SanctionUpdateRequest(null, SanctionStatus.APPLIED), admin);

        assertEquals(SanctionStatus.APPLIED, sanction.getStatus());
        verify(eventService).record(sanction.getClaim().getClient(), admin, ClientEventType.SANCTION,
                "Санкция «приостановка» по претензии №ПР-1: статус PLANNED → APPLIED");
    }

    @Test
    void updateSanctionThrowsForUnknownSanction() {
        when(sanctionRepository.findById(42L)).thenReturn(Optional.empty());

        DomainException exception = assertThrows(DomainException.class, () -> claimService.updateSanction(
                42L, new SanctionUpdateRequest(null, SanctionStatus.APPLIED), admin));
        assertEquals(ErrorCode.SANCTION_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void getByClientGroupsSanctionsUnderClaims() {
        Claim first = claim();
        Claim second = Claim.builder()
                .id(6L).client(client()).number("ПР-2")
                .claimDate(LocalDate.of(2026, 11, 2)).status(ClaimStatus.SENT).build();
        when(clientRepository.existsById(1L)).thenReturn(true);
        when(claimRepository.findByClientIdOrderByIdAsc(1L)).thenReturn(List.of(first, second));
        when(sanctionRepository.findByClaimIdInOrderByIdAsc(List.of(5L, 6L))).thenReturn(List.of(
                Sanction.builder().id(9L).claim(first).name("s1").status(SanctionStatus.APPLIED).build(),
                Sanction.builder().id(10L).claim(second).name("s2").status(SanctionStatus.PLANNED).build()));

        List<ClaimResponse> response = claimService.getByClient(1L);

        assertEquals(2, response.size());
        assertEquals(List.of("s1"), response.get(0).sanctions().stream().map(SanctionResponse::name).toList());
        assertEquals(List.of("s2"), response.get(1).sanctions().stream().map(SanctionResponse::name).toList());
    }
}

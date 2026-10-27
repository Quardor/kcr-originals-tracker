package ru.quard0r.kcrbackend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import ru.quard0r.kcrbackend.dto.request.ClientCreateRequest;
import ru.quard0r.kcrbackend.dto.request.ClientUpdateRequest;
import ru.quard0r.kcrbackend.dto.response.ClientResponse;
import ru.quard0r.kcrbackend.dto.response.PageResponse;
import ru.quard0r.kcrbackend.dto.response.SnapshotResponse;
import ru.quard0r.kcrbackend.exception.ClientAlreadyExistsException;
import ru.quard0r.kcrbackend.exception.ClientNotFoundException;
import ru.quard0r.kcrbackend.exception.base.BadRequestException;
import ru.quard0r.kcrbackend.model.Client;
import ru.quard0r.kcrbackend.model.DocumentTransferSnapshot;
import ru.quard0r.kcrbackend.repository.ClientRepository;
import ru.quard0r.kcrbackend.repository.DocumentTransferSnapshotRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientServiceTest {

    @Mock
    private ClientRepository clientRepository;

    @Mock
    private DocumentTransferSnapshotRepository snapshotRepository;

    @InjectMocks
    private ClientService clientService;

    private Client client() {
        return Client.builder()
                .id(1L)
                .kcrCode("kcr_001")
                .inn("123456789012")
                .organizationName("Organization")
                .build();
    }

    @Test
    void getClientReturnsClientResponse() {
        when(clientRepository.findById(1L)).thenReturn(Optional.of(client()));

        ClientResponse response = clientService.getClient(1L);

        assertEquals(1L, response.id());
        assertEquals("kcr_001", response.kcrCode());
        assertEquals("123456789012", response.inn());
        assertEquals("Organization", response.organizationName());
    }

    @Test
    void getClientThrowsForUnknownClient() {
        when(clientRepository.findById(42L)).thenReturn(Optional.empty());

        assertThrows(ClientNotFoundException.class, () -> clientService.getClient(42L));
    }

    @Test
    void findClientsMapsPageToPageResponse() {
        Page<Client> clients = new PageImpl<>(List.of(client()), PageRequest.of(0, 20), 1);
        when(clientRepository.findAll(ArgumentMatchers.<Specification<Client>>any(), any(Pageable.class)))
                .thenReturn(clients);

        PageResponse<ClientResponse> response = clientService.findClients(
                "kcr", null, null, null, null, PageRequest.of(0, 20));

        assertEquals(1, response.totalElements());
        assertEquals(1, response.totalPages());
        assertEquals("kcr_001", response.content().get(0).kcrCode());
    }

    @Test
    void getSnapshotsReturnsDtosWithDerivedPercent() {
        when(clientRepository.existsById(1L)).thenReturn(true);
        DocumentTransferSnapshot snapshot = DocumentTransferSnapshot.builder()
                .id(5L)
                .client(client())
                .reportingPeriod("1 квартал 2026")
                .snapshotDate(LocalDate.of(2026, 4, 10))
                .totalDocuments(10)
                .notTransferredDocuments(3)
                .build();
        when(snapshotRepository.findByClientIdOrderBySnapshotDateAscIdAsc(1L)).thenReturn(List.of(snapshot));

        List<SnapshotResponse> response = clientService.getSnapshots(1L, null);

        assertEquals(1, response.size());
        assertEquals(5L, response.get(0).id());
        assertEquals(1L, response.get(0).clientId());
        assertEquals(30.0, response.get(0).percentNotTransferred());
    }

    @Test
    void getSnapshotsWithoutTotalHasNullPercent() {
        when(clientRepository.existsById(1L)).thenReturn(true);
        DocumentTransferSnapshot snapshot = DocumentTransferSnapshot.builder()
                .id(6L)
                .client(client())
                .reportingPeriod("1 квартал 2026")
                .snapshotDate(LocalDate.of(2026, 4, 20))
                .notTransferredDocuments(1)
                .build();
        when(snapshotRepository.findByClientIdOrderBySnapshotDateAscIdAsc(1L)).thenReturn(List.of(snapshot));

        List<SnapshotResponse> response = clientService.getSnapshots(1L, null);

        assertNull(response.get(0).percentNotTransferred());
    }

    @Test
    void getSnapshotsFiltersByTrimmedReportingPeriod() {
        when(clientRepository.existsById(1L)).thenReturn(true);
        when(snapshotRepository.findByClientIdAndReportingPeriodOrderBySnapshotDateAscIdAsc(
                1L, "1 квартал 2026")).thenReturn(List.of());

        clientService.getSnapshots(1L, " 1 квартал 2026 ");

        verify(snapshotRepository).findByClientIdAndReportingPeriodOrderBySnapshotDateAscIdAsc(1L, "1 квартал 2026");
        verify(snapshotRepository, never()).findByClientIdOrderBySnapshotDateAscIdAsc(any());
    }

    @Test
    void getSnapshotsThrowsForUnknownClient() {
        when(clientRepository.existsById(42L)).thenReturn(false);

        assertThrows(ClientNotFoundException.class, () -> clientService.getSnapshots(42L, null));
    }

    @Test
    void createSavesClientAndReturnsResponse() {
        when(clientRepository.existsByKcrCode("kcr_new")).thenReturn(false);
        when(clientRepository.save(any(Client.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClientResponse response = clientService.create(new ClientCreateRequest(
                "kcr_new", "123456789012", null, "New Organization",
                null, null, null, null, null, null, null, null, null, null, null));

        assertEquals("kcr_new", response.kcrCode());
        assertEquals("New Organization", response.organizationName());

        ArgumentCaptor<Client> captor = ArgumentCaptor.forClass(Client.class);
        verify(clientRepository).save(captor.capture());
        assertEquals("kcr_new", captor.getValue().getKcrCode());
        assertEquals("123456789012", captor.getValue().getInn());
        assertEquals("New Organization", captor.getValue().getOrganizationName());
    }

    @Test
    void createRejectsDuplicateKcrCode() {
        when(clientRepository.existsByKcrCode("kcr_001")).thenReturn(true);

        assertThrows(ClientAlreadyExistsException.class, () -> clientService.create(new ClientCreateRequest(
                "kcr_001", "123456789012", null, "Organization",
                null, null, null, null, null, null, null, null, null, null, null)));
        verify(clientRepository, never()).save(any());
    }

    @Test
    void updateAppliesOnlyProvidedFields() {
        Client client = client();
        client.setTariff("old-tariff");
        when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
        when(clientRepository.save(any(Client.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClientResponse response = clientService.update(1L, new ClientUpdateRequest(
                null, null, "New Name", null, null, null, null, "new-tariff",
                null, null, null, null, null, null));

        assertEquals("New Name", response.organizationName());
        assertEquals("new-tariff", response.tariff());
        assertEquals("kcr_001", response.kcrCode());
        assertEquals("123456789012", response.inn());
    }

    @Test
    void updateRejectsBlankOrganizationName() {
        when(clientRepository.findById(1L)).thenReturn(Optional.of(client()));

        assertThrows(BadRequestException.class, () -> clientService.update(1L, new ClientUpdateRequest(
                null, null, "   ", null, null, null, null, null,
                null, null, null, null, null, null)));
        verify(clientRepository, never()).save(any());
    }

    @Test
    void updateThrowsForUnknownClient() {
        when(clientRepository.findById(42L)).thenReturn(Optional.empty());

        assertThrows(ClientNotFoundException.class, () -> clientService.update(42L, new ClientUpdateRequest(
                null, null, "New Name", null, null, null, null, null,
                null, null, null, null, null, null)));
    }
}

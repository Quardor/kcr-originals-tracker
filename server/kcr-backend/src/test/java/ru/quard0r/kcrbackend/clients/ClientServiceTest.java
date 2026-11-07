package ru.quard0r.kcrbackend.clients;

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
import ru.quard0r.kcrbackend.clients.dto.AssignmentUpdateRequest;
import ru.quard0r.kcrbackend.clients.dto.ClientCreateRequest;
import ru.quard0r.kcrbackend.clients.dto.ClientUpdateRequest;
import ru.quard0r.kcrbackend.clients.dto.ClientResponse;
import ru.quard0r.kcrbackend.clients.dto.StageUpdateRequest;
import ru.quard0r.kcrbackend.clients.events.ClientEventService;
import ru.quard0r.kcrbackend.clients.events.ClientEventType;
import ru.quard0r.kcrbackend.clients.model.Client;
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

    @Mock
    private UserRepository userRepository;

    @Mock
    private ClientEventService eventService;

    @InjectMocks
    private ClientService clientService;

    private Client client() {
        return Client.builder()
                .id(1L)
                .kcrCode("ABC12345")
                .inn("123456789012")
                .organizationName("Organization")
                .build();
    }

    private User user(long id, String username, Role role) {
        return User.builder()
                .id(id)
                .username(username)
                .password("password123")
                .role(role)
                .build();
    }

    @Test
    void getClientReturnsClientResponse() {
        when(clientRepository.findById(1L)).thenReturn(Optional.of(client()));

        ClientResponse response = clientService.getClient(1L);

        assertEquals(1L, response.id());
        assertEquals("ABC12345", response.kcrCode());
        assertEquals("123456789012", response.inn());
        assertEquals("Organization", response.organizationName());
    }

    @Test
    void getClientThrowsForUnknownClient() {
        when(clientRepository.findById(42L)).thenReturn(Optional.empty());

        DomainException exception = assertThrows(DomainException.class,
                () -> clientService.getClient(42L));
        assertEquals(ErrorCode.CLIENT_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void findClientsMapsPageToPageResponse() {
        Page<Client> clients = new PageImpl<>(List.of(client()), PageRequest.of(0, 20), 1);
        when(clientRepository.findAll(ArgumentMatchers.<Specification<Client>>any(), any(Pageable.class)))
                .thenReturn(clients);

        PageResponse<ClientResponse> response = clientService.findClients(
                "kcr", null, null, null, null, null, null, null, PageRequest.of(0, 20));

        assertEquals(1, response.totalElements());
        assertEquals(1, response.totalPages());
        assertEquals("ABC12345", response.content().get(0).kcrCode());
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

        assertThrows(DomainException.class, () -> clientService.getSnapshots(42L, null));
    }

    @Test
    void createSavesClientAndReturnsResponse() {
        when(clientRepository.existsByKcrCode("XYZ12345")).thenReturn(false);
        when(clientRepository.save(any(Client.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClientResponse response = clientService.create(new ClientCreateRequest(
                "XYZ12345", "123456789012", null, "New Organization",
                null, null, null, null, null, null, null, null, null, null, null));

        assertEquals("XYZ12345", response.kcrCode());
        assertEquals("New Organization", response.organizationName());

        ArgumentCaptor<Client> captor = ArgumentCaptor.forClass(Client.class);
        verify(clientRepository).save(captor.capture());
        assertEquals("XYZ12345", captor.getValue().getKcrCode());
        assertEquals("123456789012", captor.getValue().getInn());
        assertEquals("New Organization", captor.getValue().getOrganizationName());
    }

    @Test
    void createRejectsDuplicateKcrCode() {
        when(clientRepository.existsByKcrCode("ABC12345")).thenReturn(true);

        DomainException exception = assertThrows(DomainException.class, () -> clientService.create(
                new ClientCreateRequest(
                        "ABC12345", "123456789012", null, "Organization",
                        null, null, null, null, null, null, null, null, null, null, null)));
        assertEquals(ErrorCode.CLIENT_ALREADY_EXISTS, exception.getErrorCode());
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
        assertEquals("ABC12345", response.kcrCode());
        assertEquals("123456789012", response.inn());
    }

    @Test
    void updateRejectsBlankOrganizationName() {
        when(clientRepository.findById(1L)).thenReturn(Optional.of(client()));

        DomainException exception = assertThrows(DomainException.class, () -> clientService.update(1L,
                new ClientUpdateRequest(
                        null, null, "   ", null, null, null, null, null,
                        null, null, null, null, null, null)));
        assertEquals(ErrorCode.BLANK_ORGANIZATION_NAME, exception.getErrorCode());
        verify(clientRepository, never()).save(any());
    }

    @Test
    void updateThrowsForUnknownClient() {
        when(clientRepository.findById(42L)).thenReturn(Optional.empty());

        assertThrows(DomainException.class, () -> clientService.update(42L, new ClientUpdateRequest(
                null, null, "New Name", null, null, null, null, null,
                null, null, null, null, null, null)));
    }

    @Test
    void takeAssignsFreeClientAndRecordsEvent() {
        User operator = user(7L, "operator", Role.OPERATOR);
        Client assigned = client();
        assigned.setAssignee(operator);
        when(clientRepository.assignAssigneeIfFree(1L, 7L)).thenReturn(1);
        when(clientRepository.findById(1L)).thenReturn(Optional.of(assigned));

        ClientResponse response = clientService.take(1L, operator);

        assertEquals(7L, response.assigneeId());
        assertEquals("operator", response.assigneeUsername());
        verify(eventService).record(assigned, operator, ClientEventType.ASSIGNEE_CHANGED,
                "Взят в работу: operator");
    }

    @Test
    void takeThrowsForClientTakenByAnotherOperator() {
        when(clientRepository.assignAssigneeIfFree(1L, 7L)).thenReturn(0);
        when(clientRepository.existsById(1L)).thenReturn(true);
        User operator = user(7L, "operator", Role.OPERATOR);

        DomainException exception = assertThrows(DomainException.class,
                () -> clientService.take(1L, operator));
        assertEquals(ErrorCode.CLIENT_ALREADY_TAKEN, exception.getErrorCode());
        verify(eventService, never()).record(any(), any(), any(), any());
    }

    @Test
    void takeThrowsForUnknownClient() {
        when(clientRepository.assignAssigneeIfFree(42L, 7L)).thenReturn(0);
        when(clientRepository.existsById(42L)).thenReturn(false);

        DomainException exception = assertThrows(DomainException.class,
                () -> clientService.take(42L, user(7L, "operator", Role.OPERATOR)));
        assertEquals(ErrorCode.CLIENT_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void updateAssignmentSetsAssigneeAndRecordsEvent() {
        User admin = user(1L, "admin", Role.ADMIN);
        User operator = user(7L, "operator", Role.OPERATOR);
        Client client = client();
        when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
        when(userRepository.findById(7L)).thenReturn(Optional.of(operator));
        when(clientRepository.save(any(Client.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClientResponse response = clientService.updateAssignment(1L,
                new AssignmentUpdateRequest(7L), admin);

        assertEquals(7L, response.assigneeId());
        verify(eventService).record(client, admin, ClientEventType.ASSIGNEE_CHANGED,
                "Ответственный: — → operator");
    }

    @Test
    void updateAssignmentReleasesAssignee() {
        User admin = user(1L, "admin", Role.ADMIN);
        User operator = user(7L, "operator", Role.OPERATOR);
        Client client = client();
        client.setAssignee(operator);
        when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
        when(clientRepository.save(any(Client.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClientResponse response = clientService.updateAssignment(1L,
                new AssignmentUpdateRequest(null), admin);

        assertNull(response.assigneeId());
        verify(eventService).record(client, admin, ClientEventType.ASSIGNEE_CHANGED,
                "Ответственный: operator → освобождён");
    }

    @Test
    void updateAssignmentThrowsForUnknownUser() {
        when(clientRepository.findById(1L)).thenReturn(Optional.of(client()));
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        DomainException exception = assertThrows(DomainException.class, () -> clientService.updateAssignment(
                1L, new AssignmentUpdateRequest(99L), user(1L, "admin", Role.ADMIN)));
        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void updateStageAllowsOperatorOnOwnClientForWorkStage() {
        User operator = user(7L, "operator", Role.OPERATOR);
        Client client = client();
        client.setAssignee(operator);
        when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
        when(clientRepository.save(any(Client.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClientResponse response = clientService.updateStage(1L,
                new StageUpdateRequest(WorkStage.FIRST_CALL, "дозвонились"), operator);

        assertEquals(WorkStage.FIRST_CALL, response.stage());
        verify(eventService).record(client, operator, ClientEventType.STAGE_CHANGED,
                "Этап: NOT_IN_WORK → FIRST_CALL. дозвонились");
    }

    @Test
    void updateStageForbidsOperatorOnForeignClient() {
        User operator = user(7L, "operator", Role.OPERATOR);
        Client client = client();
        client.setAssignee(user(8L, "other", Role.OPERATOR));
        when(clientRepository.findById(1L)).thenReturn(Optional.of(client));

        DomainException exception = assertThrows(DomainException.class, () -> clientService.updateStage(
                1L, new StageUpdateRequest(WorkStage.FIRST_CALL, null), operator));
        assertEquals(ErrorCode.STAGE_CHANGE_FORBIDDEN, exception.getErrorCode());
        verify(clientRepository, never()).save(any());
    }

    @Test
    void updateStageForbidsOperatorPoolStages() {
        User operator = user(7L, "operator", Role.OPERATOR);
        Client client = client();
        client.setAssignee(operator);
        when(clientRepository.findById(1L)).thenReturn(Optional.of(client));

        DomainException exception = assertThrows(DomainException.class, () -> clientService.updateStage(
                1L, new StageUpdateRequest(WorkStage.IN_POOL, null), operator));
        assertEquals(ErrorCode.STAGE_CHANGE_FORBIDDEN, exception.getErrorCode());
    }

    @Test
    void updateStageAllowsAdminAnyStageAndSkipsNoOpChange() {
        User admin = user(1L, "admin", Role.ADMIN);
        Client client = client();
        client.setStage(WorkStage.FIRST_CALL);
        when(clientRepository.findById(1L)).thenReturn(Optional.of(client));

        ClientResponse response = clientService.updateStage(1L,
                new StageUpdateRequest(WorkStage.FIRST_CALL, null), admin);

        assertEquals(WorkStage.FIRST_CALL, response.stage());
        verify(eventService, never()).record(any(), any(), any(), any());
        verify(clientRepository, never()).save(any());
    }
}

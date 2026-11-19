package ru.quard0r.kcrbackend.clients;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;
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
import ru.quard0r.kcrbackend.snapshots.model.DocumentTransferSnapshot;
import ru.quard0r.kcrbackend.snapshots.model.DynamicsCategory;
import ru.quard0r.kcrbackend.snapshots.repository.DocumentTransferSnapshotRepository;
import ru.quard0r.kcrbackend.users.model.Role;
import ru.quard0r.kcrbackend.users.model.User;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorklistServiceTest
{
    @Mock
    private ClientRepository clientRepository;

    @Mock
    private DocumentTransferSnapshotRepository snapshotRepository;

    @Mock
    private CallListItemRepository callListItemRepository;

    @Mock
    private ClientEventService eventService;

    @Spy
    private DynamicsService dynamicsService = new DynamicsService();

    @InjectMocks
    private WorklistService worklistService;

    private User admin;
    private Client client1;
    private Client client2;
    private DocumentTransferSnapshot snap0;
    private DocumentTransferSnapshot snap1;
    private DocumentTransferSnapshot snap2;

    @BeforeEach
    void setUp()
    {
        admin = User.builder().id(1L).username("admin").role(Role.ADMIN).build();

        client1 = Client.builder()
                .id(10L)
                .kcrCode("AAA11111")
                .inn("1234567890")
                .organizationName("ООО Ромашка")
                .stage(WorkStage.NOT_IN_WORK)
                .createdAt(LocalDate.of(2022, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC))
                .build();

        client2 = Client.builder()
                .id(20L)
                .kcrCode("BBB22222")
                .inn("0987654321")
                .organizationName("ООО Лютик")
                .stage(WorkStage.NOT_IN_WORK)
                .createdAt(LocalDate.of(2022, 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC))
                .build();

        // client1: два среза с 10 непереданными → UNCHANGED_OR_GROWING
        snap0 = DocumentTransferSnapshot.builder()
                .id(100L)
                .client(client1)
                .reportingPeriod("2026-Q1")
                .snapshotDate(LocalDate.of(2026, 3, 15))
                .totalDocuments(20)
                .notTransferredDocuments(10)
                .build();

        snap1 = DocumentTransferSnapshot.builder()
                .id(101L)
                .client(client1)
                .reportingPeriod("2026-Q1")
                .snapshotDate(LocalDate.of(2026, 4, 15))
                .totalDocuments(20)
                .notTransferredDocuments(10)
                .build();

        // client2: 0 непереданных → NO_DEBT
        snap2 = DocumentTransferSnapshot.builder()
                .id(102L)
                .client(client2)
                .reportingPeriod("2026-Q1")
                .snapshotDate(LocalDate.of(2026, 4, 15))
                .totalDocuments(20)
                .notTransferredDocuments(0)
                .build();
    }

    @Test
    void previewCallList_filtersByDebtAndPercentAndLowDynamics()
    {
        when(clientRepository.findAll(any(Specification.class))).thenReturn(List.of(client1, client2));
        when(snapshotRepository.findByClientIdIn(anyCollection())).thenReturn(List.of(snap0, snap1, snap2));

        WorklistFilterRequest request = new WorklistFilterRequest(
                null, null, true, 1, 5.0, null, true, null, null
        );

        List<CallListClientResponse> preview = worklistService.previewCallList(request);

        // Только client1 подходит: долг 10 >= 1, процент 50% >= 5%, динамика UNCHANGED_OR_GROWING
        assertEquals(1, preview.size());
        assertEquals(client1.getId(), preview.get(0).clientId());
        assertEquals(10, preview.get(0).notTransferredDocuments());
        assertEquals(50.0, preview.get(0).percentNotTransferred());
        assertEquals(DynamicsCategory.UNCHANGED_OR_GROWING, preview.get(0).dynamicsCategory());
    }

    @Test
    void generateCallList_savesItemsAndSetsStageInPool()
    {
        when(clientRepository.findAll(any(Specification.class))).thenReturn(List.of(client1));
        when(snapshotRepository.findByClientIdIn(anyCollection())).thenReturn(List.of(snap1));

        WorklistFilterRequest request = new WorklistFilterRequest(
                null, null, true, 1, 5.0, null, null, null, null
        );

        CallListSummaryResponse response = worklistService.generateCallList(request, admin);

        verify(callListItemRepository).deleteAllFast();
        verify(callListItemRepository).saveAll(any());
        assertEquals(WorkStage.IN_POOL, client1.getStage());
        verify(clientRepository).save(client1);
        verify(eventService).record(eq(client1), eq(admin), eq(ClientEventType.STAGE_CHANGED), any());

        assertEquals(1, response.totalClients());
        assertEquals("admin", response.updatedByUsername());
        assertEquals(client1.getId(), response.clients().get(0).clientId());
    }

    @Test
    void getCallList_returnsSavedItemsWithDebtInfo()
    {
        CallListItem item = CallListItem.builder()
                .id(1L)
                .client(client1)
                .createdBy(admin)
                .createdAt(Instant.now())
                .position(0)
                .build();

        when(callListItemRepository.findAllWithClientsOrderByPositionAsc()).thenReturn(List.of(item));
        when(snapshotRepository.findByClientIdIn(anyCollection())).thenReturn(List.of(snap1));

        CallListSummaryResponse response = worklistService.getCallList();

        assertEquals(1, response.totalClients());
        assertEquals("admin", response.updatedByUsername());
        assertEquals(client1.getId(), response.clients().get(0).clientId());
        assertEquals(10, response.clients().get(0).notTransferredDocuments());
    }

    @Test
    void clearCallList_deletesAllItems()
    {
        worklistService.clearCallList(admin);
        verify(callListItemRepository).deleteAllFast();
    }
}

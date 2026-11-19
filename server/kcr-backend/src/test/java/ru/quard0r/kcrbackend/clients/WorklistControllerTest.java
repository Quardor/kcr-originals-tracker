package ru.quard0r.kcrbackend.clients;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import ru.quard0r.kcrbackend.clients.dto.CallListClientResponse;
import ru.quard0r.kcrbackend.clients.dto.CallListSummaryResponse;
import ru.quard0r.kcrbackend.clients.dto.WorklistFilterRequest;
import ru.quard0r.kcrbackend.security.CustomUserDetails;
import ru.quard0r.kcrbackend.users.model.Role;
import ru.quard0r.kcrbackend.users.model.User;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorklistControllerTest
{
    @Mock
    private ClientService clientService;

    @Mock
    private WorklistService worklistService;

    @InjectMocks
    private WorklistController worklistController;

    private User admin;
    private CustomUserDetails userDetails;

    @BeforeEach
    void setUp()
    {
        admin = User.builder().id(1L).username("admin").role(Role.ADMIN).build();
        userDetails = new CustomUserDetails(admin);
    }

    @Test
    void previewCallList_delegatesToService()
    {
        WorklistFilterRequest request = new WorklistFilterRequest(
                null, null, true, 1, 5.0, null, true, null, null
        );
        CallListClientResponse response = CallListClientResponse.builder()
                .clientId(10L)
                .kcrCode("AAA11111")
                .organizationName("ООО Ромашка")
                .build();

        when(worklistService.previewCallList(request)).thenReturn(List.of(response));

        ResponseEntity<List<CallListClientResponse>> result = worklistController.previewCallList(request);

        assertEquals(200, result.getStatusCode().value());
        assertEquals(1, result.getBody().size());
        assertEquals(10L, result.getBody().get(0).clientId());
    }

    @Test
    void generateCallList_delegatesToServiceWithAdminUser()
    {
        WorklistFilterRequest request = new WorklistFilterRequest(
                null, null, true, 1, 5.0, null, true, null, null
        );
        CallListSummaryResponse summary = CallListSummaryResponse.builder()
                .totalClients(1)
                .updatedAt(Instant.now())
                .updatedByUsername("admin")
                .clients(List.of())
                .build();

        when(worklistService.generateCallList(request, admin)).thenReturn(summary);

        ResponseEntity<CallListSummaryResponse> result = worklistController.generateCallList(request, userDetails);

        assertEquals(200, result.getStatusCode().value());
        assertEquals(1, result.getBody().totalClients());
        assertEquals("admin", result.getBody().updatedByUsername());
    }

    @Test
    void getCallList_delegatesToService()
    {
        CallListSummaryResponse summary = CallListSummaryResponse.builder()
                .totalClients(0)
                .clients(List.of())
                .build();

        when(worklistService.getCallList()).thenReturn(summary);

        ResponseEntity<CallListSummaryResponse> result = worklistController.getCallList();

        assertEquals(200, result.getStatusCode().value());
        assertEquals(0, result.getBody().totalClients());
    }

    @Test
    void clearCallList_delegatesToService()
    {
        ResponseEntity<Void> result = worklistController.clearCallList(userDetails);
        verify(worklistService).clearCallList(admin);
        assertEquals(204, result.getStatusCode().value());
    }
}

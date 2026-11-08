package ru.quard0r.kcrbackend.clients.events;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import ru.quard0r.kcrbackend.clients.events.dto.EventCreateRequest;
import ru.quard0r.kcrbackend.clients.events.dto.EventResponse;
import ru.quard0r.kcrbackend.clients.model.Client;
import ru.quard0r.kcrbackend.clients.model.WorkStage;
import ru.quard0r.kcrbackend.clients.repository.ClientRepository;
import ru.quard0r.kcrbackend.common.PageResponse;
import ru.quard0r.kcrbackend.common.error.DomainException;
import ru.quard0r.kcrbackend.common.error.ErrorCode;
import ru.quard0r.kcrbackend.users.model.Role;
import ru.quard0r.kcrbackend.users.model.User;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ClientEventServiceTest {

    @Mock
    private ClientEventRepository eventRepository;

    @Mock
    private ClientRepository clientRepository;

    @InjectMocks
    private ClientEventService eventService;

    private final User operator = User.builder()
            .id(7L).username("operator").password("password123").role(Role.OPERATOR).build();

    private Client ownClient() {
        Client client = Client.builder()
                .id(1L).kcrCode("ABC12345").inn("123456789012")
                .organizationName("Organization").assignee(operator)
                .build();
        client.setStage(WorkStage.NOT_IN_WORK);
        return client;
    }

    @Test
    void getEventsRequiresExistingClient() {
        when(clientRepository.existsById(42L)).thenReturn(false);

        assertThrows(DomainException.class, () -> eventService.getEvents(42L, pageable()));
    }

    @Test
    void getEventsMapsPageToResponse() {
        when(clientRepository.existsById(1L)).thenReturn(true);
        when(eventRepository.findByClientId(any(Long.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        PageResponse<EventResponse> response = eventService.getEvents(1L, pageable());

        assertEquals(0, response.totalElements());
    }

    @Test
    void createRejectsSystemEventTypes() {
        when(clientRepository.findById(1L)).thenReturn(Optional.of(ownClient()));

        DomainException exception = assertThrows(DomainException.class, () -> eventService.create(1L,
                new EventCreateRequest(ClientEventType.IMPORT, null, null, null, null, null, null, null),
                operator));
        assertEquals(ErrorCode.EVENT_TYPE_NOT_ALLOWED, exception.getErrorCode());
        verify(eventRepository, never()).save(any());
    }

    @Test
    void createRequiresCallResultForCall() {
        when(clientRepository.findById(1L)).thenReturn(Optional.of(ownClient()));

        DomainException exception = assertThrows(DomainException.class, () -> eventService.create(1L,
                new EventCreateRequest(ClientEventType.CALL, null, null, null, null, null, null, null),
                operator));
        assertEquals(ErrorCode.CALL_RESULT_REQUIRED, exception.getErrorCode());
    }

    @Test
    void createRejectsCallOnlyFieldsForOtherTypes() {
        when(clientRepository.findById(1L)).thenReturn(Optional.of(ownClient()));

        DomainException exception = assertThrows(DomainException.class, () -> eventService.create(1L,
                new EventCreateRequest(ClientEventType.COMMENT, "ответ клиента",
                        null, "обещал передать", null, null, null, null),
                operator));
        assertEquals(ErrorCode.EVENT_FIELD_NOT_ALLOWED, exception.getErrorCode());
    }

    @Test
    void createRejectsStageForNonCallTypes() {
        when(clientRepository.findById(1L)).thenReturn(Optional.of(ownClient()));

        DomainException exception = assertThrows(DomainException.class, () -> eventService.create(1L,
                new EventCreateRequest(ClientEventType.COMMENT, "комментарий",
                        null, null, null, null, null, WorkStage.RECHECK),
                operator));
        assertEquals(ErrorCode.EVENT_FIELD_NOT_ALLOWED, exception.getErrorCode());
    }

    @Test
    void createCallUpdatesNextContactAndStageWithSeparateStageEvent() {
        Client client = ownClient();
        when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
        LocalDate nextContact = LocalDate.of(2026, 11, 20);
        when(eventRepository.save(any(ClientEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        eventService.create(1L, new EventCreateRequest(ClientEventType.CALL,
                "звонок", CallResult.CONNECTED, "передаст до пятницы",
                LocalDate.of(2026, 11, 22), nextContact, "https://calls.local/1",
                WorkStage.FIRST_CALL), operator);

        assertEquals(nextContact, client.getNextContactAt());
        assertEquals(WorkStage.FIRST_CALL, client.getStage());
        ArgumentCaptor<ClientEvent> captor = ArgumentCaptor.forClass(ClientEvent.class);
        verify(eventRepository, times(2)).save(captor.capture());
        assertEquals(ClientEventType.STAGE_CHANGED, captor.getAllValues().get(0).getType());
        assertEquals("Этап: NOT_IN_WORK → FIRST_CALL", captor.getAllValues().get(0).getComment());
        assertEquals(ClientEventType.CALL, captor.getAllValues().get(1).getType());
        assertEquals(CallResult.CONNECTED, captor.getAllValues().get(1).getCallResult());
    }

    @Test
    void createCallWithSameStageDoesNotRecordStageEvent() {
        Client client = ownClient();
        client.setStage(WorkStage.FIRST_CALL);
        when(clientRepository.findById(1L)).thenReturn(Optional.of(client));
        when(eventRepository.save(any(ClientEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        eventService.create(1L, new EventCreateRequest(ClientEventType.CALL,
                "звонок", CallResult.NO_ANSWER, null, null, null, null,
                WorkStage.FIRST_CALL), operator);

        verify(eventRepository, times(1)).save(any(ClientEvent.class));
    }

    @Test
    void createCallStageChangeFollowsStagePolicy() {
        User stranger = User.builder()
                .id(8L).username("stranger").password("password123").role(Role.OPERATOR).build();
        when(clientRepository.findById(1L)).thenReturn(Optional.of(ownClient()));

        DomainException exception = assertThrows(DomainException.class, () -> eventService.create(1L,
                new EventCreateRequest(ClientEventType.CALL, "звонок",
                        CallResult.CONNECTED, null, null, null, null, WorkStage.FIRST_CALL),
                stranger));
        assertEquals(ErrorCode.STAGE_CHANGE_FORBIDDEN, exception.getErrorCode());
        verify(eventRepository, never()).save(any());
    }

    @Test
    void createLetterSentAcceptsCommentOnly() {
        when(clientRepository.findById(1L)).thenReturn(Optional.of(ownClient()));
        when(eventRepository.save(any(ClientEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EventResponse response = eventService.create(1L, new EventCreateRequest(
                ClientEventType.LETTER_SENT, "требование отправлено",
                null, null, null, null, null, null), operator);

        assertEquals(ClientEventType.LETTER_SENT, response.type());
        assertEquals("operator", response.authorUsername());
    }

    private Pageable pageable() {
        return PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
    }
}

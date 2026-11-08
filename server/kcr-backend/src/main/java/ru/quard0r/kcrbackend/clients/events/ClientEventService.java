package ru.quard0r.kcrbackend.clients.events;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.quard0r.kcrbackend.clients.StagePolicy;
import ru.quard0r.kcrbackend.clients.events.dto.EventCreateRequest;
import ru.quard0r.kcrbackend.clients.events.dto.EventResponse;
import ru.quard0r.kcrbackend.clients.model.Client;
import ru.quard0r.kcrbackend.clients.model.WorkStage;
import ru.quard0r.kcrbackend.clients.repository.ClientRepository;
import ru.quard0r.kcrbackend.common.PageResponse;
import ru.quard0r.kcrbackend.common.error.DomainException;
import ru.quard0r.kcrbackend.common.error.ErrorCode;
import ru.quard0r.kcrbackend.model.User;

/**
 * Единая история клиента (§11.2–11.4): выдача хронологически от новых к старым,
 * ручное создание событий CALL/LETTER_SENT/COMMENT. Звонок (CALL) обновляет
 * clients.next_contact_at и — при указании stage — меняет этап по правилам
 * StagePolicy, создавая отдельное событие STAGE_CHANGED в той же транзакции.
 * Системные события (STAGE_CHANGED, ASSIGNEE_CHANGED, IMPORT, CLAIM, SANCTION)
 * вручную не заводятся, их пишут сервисы через {@link #record}.
 */
@Service
@RequiredArgsConstructor
public class ClientEventService {

    private final ClientEventRepository eventRepository;
    private final ClientRepository clientRepository;

    @Transactional(readOnly = true)
    public PageResponse<EventResponse> getEvents(Long clientId, Pageable pageable) {
        requireClient(clientId);
        Page<EventResponse> page = eventRepository.findByClientId(clientId, pageable)
                .map(EventResponse::from);
        return PageResponse.from(page);
    }

    @Transactional
    public EventResponse create(Long clientId, EventCreateRequest request, User author) {
        Client client = getClient(clientId);
        if (!request.type().isManuallyCreatable()) {
            throw new DomainException(ErrorCode.EVENT_TYPE_NOT_ALLOWED);
        }
        if (request.type() != ClientEventType.CALL) {
            requireCallOnlyFieldsAbsent(request);
        }
        if (request.type() == ClientEventType.CALL && request.callResult() == null) {
            throw new DomainException(ErrorCode.CALL_RESULT_REQUIRED);
        }

        if (request.type() == ClientEventType.CALL && request.nextContactAt() != null) {
            client.setNextContactAt(request.nextContactAt());
        }
        if (request.stage() != null) {
            if (request.type() != ClientEventType.CALL) {
                throw new DomainException(ErrorCode.EVENT_FIELD_NOT_ALLOWED);
            }
            applyStageFromCall(client, request.stage(), author);
        }

        ClientEvent event = eventRepository.save(ClientEvent.builder()
                .client(client)
                .author(author)
                .type(request.type())
                .comment(request.comment())
                .callResult(request.callResult())
                .agreement(request.agreement())
                .clientDeadline(request.clientDeadline())
                .nextContactAt(request.nextContactAt())
                .recordUrl(request.recordUrl())
                .build());
        return EventResponse.from(event);
    }

    /**
     * Системное событие о мутации (смена этапа, переназначение, импорт, претензия,
     * санкция) — единая точка записи аудита (§11.2).
     */
    @Transactional
    public void record(Client client, User author, ClientEventType type, String comment) {
        eventRepository.save(ClientEvent.builder()
                .client(client)
                .author(author)
                .type(type)
                .comment(comment)
                .build());
    }

    private void applyStageFromCall(Client client, WorkStage target, User author) {
        if (client.getStage() == target) {
            return;
        }
        StagePolicy.assertChangeAllowed(author, client, target);
        WorkStage previous = client.getStage();
        client.setStage(target);
        record(client, author, ClientEventType.STAGE_CHANGED,
                "Этап: %s → %s".formatted(previous, target));
    }

    private void requireCallOnlyFieldsAbsent(EventCreateRequest request) {
        if (request.callResult() != null || request.agreement() != null
                || request.clientDeadline() != null || request.nextContactAt() != null
                || request.recordUrl() != null) {
            throw new DomainException(ErrorCode.EVENT_FIELD_NOT_ALLOWED);
        }
    }

    private Client getClient(Long clientId) {
        return clientRepository.findById(clientId)
                .orElseThrow(() -> new DomainException(ErrorCode.CLIENT_NOT_FOUND));
    }

    private void requireClient(Long clientId) {
        if (!clientRepository.existsById(clientId)) {
            throw new DomainException(ErrorCode.CLIENT_NOT_FOUND);
        }
    }
}

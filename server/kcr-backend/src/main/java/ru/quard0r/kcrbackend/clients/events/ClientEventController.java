package ru.quard0r.kcrbackend.clients.events;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.quard0r.kcrbackend.clients.events.dto.EventCreateRequest;
import ru.quard0r.kcrbackend.clients.events.dto.EventResponse;
import ru.quard0r.kcrbackend.common.PageLimits;
import ru.quard0r.kcrbackend.common.PageResponse;
import ru.quard0r.kcrbackend.security.CustomUserDetails;

@RestController
@RequestMapping("/api/v1/clients/{id}/events")
@RequiredArgsConstructor
public class ClientEventController {

    private final ClientEventService eventService;

    /** История клиента — новые события к старым (§11.4). */
    @GetMapping
    public ResponseEntity<PageResponse<EventResponse>> getEvents(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "page must be >= 0") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size must be >= 1")
            @Max(value = PageLimits.MAX_PAGE_SIZE, message = "size must be <= " + PageLimits.MAX_PAGE_SIZE)
            int size)
    {
        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        return ResponseEntity.ok(eventService.getEvents(id, pageRequest));
    }

    /** Создать событие вручную: CALL/LETTER_SENT/COMMENT (§11.4). */
    @PostMapping
    public ResponseEntity<EventResponse> createEvent(
            @PathVariable Long id,
            @Valid @RequestBody EventCreateRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails)
    {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(eventService.create(id, request, userDetails.user()));
    }
}

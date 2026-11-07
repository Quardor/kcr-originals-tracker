package ru.quard0r.kcrbackend.clients;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.quard0r.kcrbackend.clients.dto.ClientResponse;
import ru.quard0r.kcrbackend.common.PageLimits;
import ru.quard0r.kcrbackend.common.PageResponse;
import ru.quard0r.kcrbackend.security.CustomUserDetails;

/** Рабочие списки операторов (§11.4). */
@RestController
@RequestMapping("/api/v1/worklist")
@RequiredArgsConstructor
public class WorklistController {

    private final ClientService clientService;

    /** «Сегодня нужно»: срок следующего контакта наступил; оператору — свои, админу — все. */
    @GetMapping("/today")
    public ResponseEntity<PageResponse<ClientResponse>> findDueToday(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "page must be >= 0") int page,
            @RequestParam(defaultValue = "20")
            @Min(value = 1, message = "size must be >= 1")
            @Max(value = PageLimits.MAX_PAGE_SIZE, message = "size must be <= " + PageLimits.MAX_PAGE_SIZE)
            int size)
    {
        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Direction.ASC, "nextContactAt").and(Sort.by(Sort.Direction.ASC, "id")));
        return ResponseEntity.ok(clientService.findDueToday(userDetails.user(), pageRequest));
    }
}

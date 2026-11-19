package ru.quard0r.kcrbackend.clients;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.quard0r.kcrbackend.clients.dto.CallListClientResponse;
import ru.quard0r.kcrbackend.clients.dto.CallListSummaryResponse;
import ru.quard0r.kcrbackend.clients.dto.ClientResponse;
import ru.quard0r.kcrbackend.clients.dto.WorklistFilterRequest;
import ru.quard0r.kcrbackend.common.PageLimits;
import ru.quard0r.kcrbackend.common.PageResponse;
import ru.quard0r.kcrbackend.security.CustomUserDetails;

import java.util.List;

/** Рабочие списки операторов (§11.4 ARCHITECTURE.md). */
@RestController
@RequestMapping("/api/v1/worklist")
@RequiredArgsConstructor
public class WorklistController
{
    private final ClientService clientService;
    private final WorklistService worklistService;

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

    /** Предпросмотр списка на обзвон по фильтрам (долговая информация, процент непереданных, динамика низкая). */
    @PostMapping("/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<CallListClientResponse>> previewCallList(
            @RequestBody WorklistFilterRequest request)
    {
        return ResponseEntity.ok(worklistService.previewCallList(request));
    }

    /** Закрепить полученный список на обзвон для всех операторов (только администратор). */
    @PostMapping("/generate")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CallListSummaryResponse> generateCallList(
            @RequestBody WorklistFilterRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails)
    {
        return ResponseEntity.ok(worklistService.generateCallList(request, userDetails.user()));
    }

    /** Получить единый закреплённый список на обзвон (доступен всем операторам и админам). */
    @GetMapping("/call-list")
    @PreAuthorize("hasAnyRole('ADMIN', 'OPERATOR')")
    public ResponseEntity<CallListSummaryResponse> getCallList()
    {
        return ResponseEntity.ok(worklistService.getCallList());
    }

    /** Очистить закреплённый список на обзвон (только администратор). */
    @DeleteMapping("/call-list")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> clearCallList(
            @AuthenticationPrincipal CustomUserDetails userDetails)
    {
        worklistService.clearCallList(userDetails.user());
        return ResponseEntity.noContent().build();
    }
}

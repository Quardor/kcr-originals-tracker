package ru.quard0r.kcrbackend.ai;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.quard0r.kcrbackend.ai.dto.AiProxyRequest;
import ru.quard0r.kcrbackend.ai.dto.AiProxyResponse;
import ru.quard0r.kcrbackend.ai.dto.ChatTicketResponse;
import ru.quard0r.kcrbackend.security.CustomUserDetails;

import java.util.Arrays;

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiProxyController {

    private final AiProxyService aiProxyService;

    @GetMapping("/analytics/summary")
    public ResponseEntity<byte[]> analyticsSummary(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                   HttpServletRequest request)
    {
        return forwardGet("/analytics/summary", userDetails, request);
    }

    @GetMapping("/analytics/dynamics")
    public ResponseEntity<byte[]> analyticsDynamics(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                    HttpServletRequest request)
    {
        return forwardGet("/analytics/dynamics", userDetails, request);
    }

    @GetMapping("/analytics/top-debt")
    public ResponseEntity<byte[]> analyticsTopDebt(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                   HttpServletRequest request)
    {
        return forwardGet("/analytics/top-debt", userDetails, request);
    }

    @GetMapping("/analytics/operators")
    public ResponseEntity<byte[]> analyticsOperators(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                     HttpServletRequest request)
    {
        return forwardGet("/analytics/operators", userDetails, request);
    }

    @GetMapping("/clients/{clientId}/brief")
    public ResponseEntity<byte[]> clientBrief(@AuthenticationPrincipal CustomUserDetails userDetails,
                                              HttpServletRequest request,
                                              @PathVariable long clientId)
    {
        return forwardGet("/clients/" + clientId + "/brief", userDetails, request);
    }

    @PostMapping("/prioritize")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> prioritize(@AuthenticationPrincipal CustomUserDetails userDetails,
                                            HttpServletRequest request,
                                            @RequestBody(required = false) byte[] body)
    {
        return forwardPost("/prioritize", userDetails, request, body);
    }

    @PostMapping("/export/xlsx")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> exportXlsx(@AuthenticationPrincipal CustomUserDetails userDetails,
                                            HttpServletRequest request,
                                            @RequestBody(required = false) byte[] body)
    {
        return forwardPost("/export/xlsx", userDetails, request, body);
    }

    @PostMapping("/chat/ticket")
    public ResponseEntity<ChatTicketResponse> issueChatTicket(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                              @RequestHeader(HttpHeaders.AUTHORIZATION)
                                                              String authorization)
    {
        return ResponseEntity.ok(aiProxyService.issueChatTicket(bearerToken(authorization)));
    }

    private ResponseEntity<byte[]> forwardGet(String path, CustomUserDetails userDetails, HttpServletRequest request) {
        AiProxyRequest aiRequest = new AiProxyRequest(HttpMethod.GET.name(), path, queryParams(request), null, null,
                userDetails.user().getId(), userDetails.user().getRole().name(),
                request.getHeader(HttpHeaders.AUTHORIZATION));
        return respond(aiProxyService.forward(aiRequest));
    }

    private ResponseEntity<byte[]> forwardPost(String path, CustomUserDetails userDetails,
                                               HttpServletRequest request, byte[] body) {
        AiProxyRequest aiRequest = new AiProxyRequest(HttpMethod.POST.name(), path, queryParams(request),
                request.getContentType(), body, userDetails.user().getId(), userDetails.user().getRole().name(),
                request.getHeader(HttpHeaders.AUTHORIZATION));
        return respond(aiProxyService.forward(aiRequest));
    }

    private ResponseEntity<byte[]> respond(AiProxyResponse aiResponse) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(aiResponse.status());
        if (aiResponse.contentType() != null) {
            response.header(HttpHeaders.CONTENT_TYPE, aiResponse.contentType());
        }
        if (aiResponse.contentDisposition() != null) {
            response.header(HttpHeaders.CONTENT_DISPOSITION, aiResponse.contentDisposition());
        }
        return response.body(aiResponse.body());
    }

    private static MultiValueMap<String, String> queryParams(HttpServletRequest request) {
        MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
        request.getParameterMap().forEach((name, values) -> queryParams.put(name, Arrays.asList(values)));
        return queryParams;
    }

    private static String bearerToken(String authorization) {
        return authorization.substring(authorization.indexOf(' ') + 1).trim();
    }
}

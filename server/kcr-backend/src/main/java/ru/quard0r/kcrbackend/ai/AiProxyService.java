package ru.quard0r.kcrbackend.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.quard0r.kcrbackend.ai.dto.AiProxyRequest;
import ru.quard0r.kcrbackend.ai.dto.AiProxyResponse;
import ru.quard0r.kcrbackend.ai.dto.ChatTicketResponse;
import ru.quard0r.kcrbackend.common.error.DomainException;
import ru.quard0r.kcrbackend.common.error.ErrorCode;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
@RequiredArgsConstructor
public class AiProxyService {

    static final String ML_INTERNAL_TOKEN_REJECTED_CODE = "INTERNAL_TOKEN_REJECTED";

    private static final int HTTP_UNAUTHORIZED = 401;

    private final MlServiceClient mlServiceClient;
    private final ChatTicketService chatTicketService;
    private final ObjectMapper objectMapper;

    public AiProxyResponse forward(AiProxyRequest request) {
        AiProxyResponse response = mlServiceClient.exchange(request);
        if (response.status() == HTTP_UNAUTHORIZED && isInternalTokenRejected(response.body())) {
            log.error("kcr-ml rejected the internal service token on {} {}: "
                    + "check that ML_INTERNAL_TOKEN and AUTH_INTERNAL_TOKEN hold the same value",
                    request.method(), request.path());
            throw new DomainException(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }
        return response;
    }

    public ChatTicketResponse issueChatTicket(String accessToken) {
        return chatTicketService.issue(accessToken);
    }

    private boolean isInternalTokenRejected(byte[] body) {
        try {
            return body != null
                    && ML_INTERNAL_TOKEN_REJECTED_CODE.equals(objectMapper.readTree(body).path("code").asString());
        } catch (JacksonException e) {
            return false;
        }
    }
}

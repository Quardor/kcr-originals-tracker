package ru.quard0r.kcrbackend.ai;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class ChatTicketBearerTokenResolver implements BearerTokenResolver {

    static final String CHAT_PATH = AiChatRelayHandler.PATH;
    static final String TICKET_PARAMETER = "ticket";
    static final String ACCESS_TOKEN_REQUEST_ATTRIBUTE =
            ChatTicketBearerTokenResolver.class.getName() + ".accessToken";

    private final BearerTokenResolver headerResolver = new DefaultBearerTokenResolver();
    private final ChatTicketService chatTicketService;

    public ChatTicketBearerTokenResolver(ChatTicketService chatTicketService) {
        this.chatTicketService = chatTicketService;
    }

    @Override
    public String resolve(HttpServletRequest request) throws InvalidBearerTokenException {
        String headerToken = headerResolver.resolve(request);
        if (headerToken != null || !isChatHandshake(request)) {
            return headerToken;
        }
        Optional<String> ticketToken = chatTicketService.consume(request.getParameter(TICKET_PARAMETER));
        if (ticketToken.isEmpty()) {
            return null;
        }
        String accessToken = ticketToken.get();
        request.setAttribute(ACCESS_TOKEN_REQUEST_ATTRIBUTE, accessToken);
        return accessToken;
    }

    private boolean isChatHandshake(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            return false;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return CHAT_PATH.equals(path);
    }
}

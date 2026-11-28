package ru.quard0r.kcrbackend.ai;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import ru.quard0r.kcrbackend.common.error.ErrorCode;
import ru.quard0r.kcrbackend.common.error.ErrorResponse;
import ru.quard0r.kcrbackend.security.CustomUserDetails;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.WebSocketHandshakeException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@Slf4j
@RequiredArgsConstructor
public class AiChatHandshakeInterceptor implements HandshakeInterceptor {

    static final String UPSTREAM_REQUEST_ATTRIBUTE = AiChatHandshakeInterceptor.class.getName() + ".upstream";

    private static final long UPSTREAM_WAIT_MILLIS = 6_000;

    private final AiChatRelayHandler aiChatRelayHandler;
    private final ObjectMapper objectMapper;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes)
            throws IOException {
        HttpServletRequest httpRequest = httpRequestOf(request);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String accessToken = accessTokenOf(authentication, httpRequest);
        if (accessToken == null) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        CustomUserDetails user = (CustomUserDetails) authentication.getPrincipal();
        CompletableFuture<AiChatLink> connection = aiChatRelayHandler.openUpstream(
                user.user().getId(), user.user().getRole().name(), accessToken);
        AiChatLink upstream = awaitUpstream(connection);
        if (upstream == null) {
            return rejectUnavailable(response);
        }
        httpRequest.setAttribute(UPSTREAM_REQUEST_ATTRIBUTE, upstream);
        attributes.put(AiChatRelayHandler.UPSTREAM_ATTRIBUTE, upstream);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        HttpServletRequest httpRequest = httpRequestOf(request);
        if (exception != null && httpRequest != null
                && httpRequest.getAttribute(UPSTREAM_REQUEST_ATTRIBUTE) instanceof AiChatLink upstream) {
            upstream.abortUpstream();
        }
    }

    private AiChatLink awaitUpstream(CompletableFuture<AiChatLink> connection) {
        try {
            return connection.get(UPSTREAM_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            log.warn("kcr-ml refused the chat connection: {}: {}", e.getCause().getClass().getSimpleName(),
                    handshakeFailure(e.getCause()));
        } catch (TimeoutException e) {
            log.warn("kcr-ml did not accept the chat connection within {} ms", UPSTREAM_WAIT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while connecting the chat to kcr-ml");
        }
        connection.cancel(true);
        return null;
    }

    private static String handshakeFailure(Throwable cause) {
        if (cause instanceof WebSocketHandshakeException handshake && handshake.getResponse() != null) {
            return "HTTP " + handshake.getResponse().statusCode()
                    + " (check that ML_INTERNAL_TOKEN and AUTH_INTERNAL_TOKEN hold the same value)";
        }
        return cause.toString();
    }

    private boolean rejectUnavailable(ServerHttpResponse response) throws IOException {
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getBody().write(objectMapper.writeValueAsBytes(new ErrorResponse(ErrorCode.AI_SERVICE_UNAVAILABLE)));
        return false;
    }

    private static HttpServletRequest httpRequestOf(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            return servletRequest.getServletRequest();
        }
        return null;
    }

    private static String accessTokenOf(Authentication authentication, HttpServletRequest httpRequest) {
        boolean authenticated = authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof CustomUserDetails;
        if (!authenticated || httpRequest == null) {
            return null;
        }
        Object accessToken = httpRequest.getAttribute(ChatTicketBearerTokenResolver.ACCESS_TOKEN_REQUEST_ATTRIBUTE);
        return accessToken instanceof String token && !token.isBlank() ? token : null;
    }
}

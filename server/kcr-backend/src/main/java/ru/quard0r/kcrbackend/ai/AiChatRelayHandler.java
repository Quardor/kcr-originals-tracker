package ru.quard0r.kcrbackend.ai;

import jakarta.websocket.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.standard.StandardWebSocketSession;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
@RequiredArgsConstructor
public class AiChatRelayHandler implements WebSocketHandler {

    public static final String PATH = "/api/v1/ai/chat";

    static final String UPSTREAM_ATTRIBUTE = AiChatRelayHandler.class.getName() + ".upstream";

    private static final int CHAT_TEXT_MESSAGE_LIMIT_BYTES = 64 * 1024;
    private static final long CHAT_SESSION_IDLE_TIMEOUT_MILLIS = 3_600_000;

    private final MlServiceClient mlServiceClient;
    private final ConcurrentHashMap<String, AiChatLink> links = new ConcurrentHashMap<>();

    CompletableFuture<AiChatLink> openUpstream(long userId, String userRole, String accessToken) {
        AiChatLink link = new AiChatLink();
        return mlServiceClient.openChat(userId, userRole, accessToken, link.upstreamListener())
                .thenApply(webSocket -> {
                    link.connected(webSocket);
                    return link;
                });
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession browserSession) throws IOException {
        AiChatLink link = upstreamOf(browserSession);
        if (link == null) {
            browserSession.close(CloseStatus.SERVER_ERROR);
            return;
        }
        relaxSessionLimits(browserSession);
        links.put(browserSession.getId(), link);
        link.attachBrowser(browserSession);
    }

    @Override
    public void handleMessage(WebSocketSession browserSession, WebSocketMessage<?> message) {
        AiChatLink link = links.get(browserSession.getId());
        if (link == null) {
            return;
        }
        if (message instanceof TextMessage text) {
            link.sendToMl(webSocket -> webSocket.sendText(text.getPayload(), text.isLast()));
        } else if (message instanceof BinaryMessage binary) {
            link.sendToMl(webSocket -> webSocket.sendBinary(binary.getPayload(), binary.isLast()));
        }
    }

    @Override
    public void handleTransportError(WebSocketSession browserSession, Throwable exception) {
        log.debug("Browser chat connection transport error: {}", exception.toString());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession browserSession, CloseStatus closeStatus) {
        AiChatLink link = links.remove(browserSession.getId());
        if (link != null) {
            link.closeUpstream();
        }
    }

    @Override
    public boolean supportsPartialMessages() {
        return true;
    }

    private static AiChatLink upstreamOf(WebSocketSession browserSession) {
        return (AiChatLink) browserSession.getAttributes().get(UPSTREAM_ATTRIBUTE);
    }

    private static void relaxSessionLimits(WebSocketSession browserSession) {
        if (browserSession instanceof StandardWebSocketSession standardSession) {
            Session nativeSession = standardSession.getNativeSession();
            nativeSession.setMaxTextMessageBufferSize(CHAT_TEXT_MESSAGE_LIMIT_BYTES);
            nativeSession.setMaxIdleTimeout(CHAT_SESSION_IDLE_TIMEOUT_MILLIS);
        }
    }
}

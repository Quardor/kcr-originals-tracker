package ru.quard0r.kcrbackend.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

@Slf4j
final class AiChatLink {

    private static final int SEND_TIME_LIMIT_MILLIS = 20_000;
    private static final int SEND_BUFFER_SIZE_LIMIT_BYTES = 256 * 1024;
    private static final int FRAMES_BEFORE_ATTACH_LIMIT = 64;
    private static final int RELAYABLE_CLOSE_CODE_MIN = 1000;
    private static final int RELAYABLE_CLOSE_CODE_MAX = 4999;
    private static final Set<Integer> RESERVED_CLOSE_CODES = Set.of(1004, 1005, 1006, 1015);

    private final Object attachMonitor = new Object();
    private final AtomicReference<WebSocket> upstream = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<WebSocket>> pendingSend =
            new AtomicReference<>(CompletableFuture.completedFuture(null));

    private WebSocketSession browserSession;
    private final List<WebSocketMessage<?>> framesBeforeAttach = new ArrayList<>();
    private CloseStatus closeStatusBeforeAttach;

    WebSocket.Listener upstreamListener() {
        return new UpstreamListener();
    }

    void connected(WebSocket webSocket) {
        upstream.set(webSocket);
    }

    void attachBrowser(WebSocketSession session) {
        List<WebSocketMessage<?>> bufferedFrames;
        CloseStatus pendingCloseStatus;
        synchronized (attachMonitor) {
            browserSession = new ConcurrentWebSocketSessionDecorator(
                    session, SEND_TIME_LIMIT_MILLIS, SEND_BUFFER_SIZE_LIMIT_BYTES);
            bufferedFrames = List.copyOf(framesBeforeAttach);
            framesBeforeAttach.clear();
            pendingCloseStatus = closeStatusBeforeAttach;
        }
        bufferedFrames.forEach(this::sendToBrowser);
        if (pendingCloseStatus != null) {
            closeBrowser(pendingCloseStatus);
        }
    }

    void sendToMl(Function<WebSocket, CompletableFuture<WebSocket>> send) {
        WebSocket mlSocket = upstream.get();
        if (mlSocket == null) {
            return;
        }
        CompletableFuture<WebSocket> sent = pendingSend.updateAndGet(previous ->
                previous.handle((webSocket, failure) -> null)
                        .thenCompose(ignored -> send.apply(mlSocket)));
        sent.exceptionally(failure -> {
            upstreamFailed("Chat frame was not delivered to kcr-ml: " + failure);
            return null;
        });
    }

    void receiveFromMl(WebSocketMessage<?> frame) {
        boolean bufferOverflowed = false;
        WebSocketSession outbound;
        synchronized (attachMonitor) {
            if (browserSession == null) {
                bufferOverflowed = framesBeforeAttach.size() >= FRAMES_BEFORE_ATTACH_LIMIT;
                if (!bufferOverflowed) {
                    framesBeforeAttach.add(frame);
                }
                outbound = null;
            } else {
                outbound = browserSession;
            }
        }
        if (bufferOverflowed) {
            upstreamFailed("kcr-ml sent too many chat frames before the browser attached");
        } else if (outbound != null) {
            sendToBrowser(outbound, frame);
        }
    }

    void upstreamClosed(int statusCode) {
        closeBrowser(relayedCloseStatus(statusCode));
    }

    void upstreamFailed(String reason) {
        log.warn(reason);
        WebSocket mlSocket = upstream.get();
        if (mlSocket != null) {
            mlSocket.abort();
        }
        closeBrowser(CloseStatus.SERVER_ERROR);
    }

    void closeUpstream() {
        WebSocket mlSocket = upstream.get();
        if (mlSocket == null) {
            return;
        }
        if (mlSocket.isOutputClosed()) {
            mlSocket.abort();
            return;
        }
        try {
            mlSocket.sendClose(WebSocket.NORMAL_CLOSURE, "").exceptionally(failure -> null);
        } catch (IllegalStateException e) {
            mlSocket.abort();
        }
    }

    void abortUpstream() {
        WebSocket mlSocket = upstream.get();
        if (mlSocket != null) {
            mlSocket.abort();
        }
    }

    private void sendToBrowser(WebSocketMessage<?> frame) {
        WebSocketSession outbound = attachedSession();
        if (outbound != null) {
            sendToBrowser(outbound, frame);
        }
    }

    private void sendToBrowser(WebSocketSession outbound, WebSocketMessage<?> frame) {
        try {
            outbound.sendMessage(frame);
        } catch (IOException | IllegalStateException e) {
            log.debug("Chat frame was not delivered to the browser: {}", e.toString());
        }
    }

    private void closeBrowser(CloseStatus closeStatus) {
        WebSocketSession outbound;
        synchronized (attachMonitor) {
            if (browserSession == null) {
                closeStatusBeforeAttach = closeStatus;
                return;
            }
            outbound = browserSession;
        }
        try {
            outbound.close(closeStatus);
        } catch (IOException e) {
            log.debug("Browser chat session was not closed: {}", e.toString());
        }
    }

    private WebSocketSession attachedSession() {
        synchronized (attachMonitor) {
            return browserSession;
        }
    }

    private static CloseStatus relayedCloseStatus(int statusCode) {
        boolean relayable = statusCode >= RELAYABLE_CLOSE_CODE_MIN
                && statusCode <= RELAYABLE_CLOSE_CODE_MAX
                && !RESERVED_CLOSE_CODES.contains(statusCode);
        return relayable ? new CloseStatus(statusCode) : CloseStatus.SERVER_ERROR;
    }

    private final class UpstreamListener implements WebSocket.Listener {

        private final StringBuilder frame = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            frame.append(data);
            if (last) {
                receiveFromMl(new TextMessage(frame.toString()));
                frame.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            upstreamClosed(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            upstreamFailed("kcr-ml chat connection failed: " + error);
        }
    }
}

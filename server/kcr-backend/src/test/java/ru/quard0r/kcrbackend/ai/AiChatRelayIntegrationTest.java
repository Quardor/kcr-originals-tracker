package ru.quard0r.kcrbackend.ai;

import okhttp3.Headers;
import okhttp3.Response;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AiChatRelayIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final int SWITCHING_PROTOCOLS = 101;
    private static final int POLICY_VIOLATION = 1008;
    private static final int FORBIDDEN = 403;
    private static final String ALLOWED_ORIGIN = "http://localhost:5173";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final AtomicReference<Function<RecordedRequest, MockResponse>> mlBehaviour =
            new AtomicReference<>();
    private static final BlockingQueue<Headers> mlHandshakes = new LinkedBlockingQueue<>();
    private static final Set<okhttp3.WebSocket> openChatSockets = ConcurrentHashMap.newKeySet();
    private static final MockWebServer ml = new MockWebServer();
    private static final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private Environment environment;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    static {
        ml.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return mlBehaviour.get().apply(request);
            }

            @Override
            public MockResponse peek() {
                return mlBehaviour.get().apply(null);
            }
        });
    }

    @DynamicPropertySource
    static void mlProperties(DynamicPropertyRegistry registry) throws IOException {
        ml.start();
        registry.add("app.ml.base-url", () -> ml.url("/").toString());
        registry.add("app.ml.connect-timeout-ms", () -> "2000");
        registry.add("app.ml.read-timeout-ms", () -> "5000");
    }

    @AfterAll
    static void stopMl() throws Exception {
        while (ml.takeRequest(100, TimeUnit.MILLISECONDS) != null) {
        }
        ml.shutdown();
    }

    @BeforeEach
    void answerWithServerError() {
        mlHandshakes.clear();
        mlBehaviour.set(request -> refusedConnection().setResponseCode(500));
    }

    @AfterEach
    void closeChatSockets() {
        openChatSockets.forEach(socket -> socket.close(WebSocket.NORMAL_CLOSURE, "test finished"));
        openChatSockets.clear();
    }

    @AfterEach
    void deleteTestUsers() {
        AiTestUsers.delete(jdbcTemplate);
    }

    @Test
    void ticketOpensChatRelayedInBothWays() throws Exception {
        String username = "ai-ws-" + suffix();
        String accessToken = registerAndLogin(username);
        String ticket = issueTicket(accessToken);
        echoChat();
        ChatClient chat = new ChatClient();

        WebSocket session = openChat("ticket=" + ticket, null, chat)
                .get(WAIT.toSeconds(), TimeUnit.SECONDS);

        session.sendText("привет", true).get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertEquals("echo:привет", chat.frames.poll(WAIT.toSeconds(), TimeUnit.SECONDS));
        String largeFrame = "ы".repeat(20_000);
        session.sendText(largeFrame, true).get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertEquals("echo:" + largeFrame, chat.frames.poll(WAIT.toSeconds(), TimeUnit.SECONDS));

        session.sendText("close-me", true).get(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertEquals(POLICY_VIOLATION, chat.closeCode.get(WAIT.toSeconds(), TimeUnit.SECONDS));

        Headers handshake = mlHandshakes.poll(WAIT.toSeconds(), TimeUnit.SECONDS);
        assertNotNull(handshake);
        assertEquals("Bearer " + accessToken, handshake.get("Authorization"));
        assertEquals(internalToken(), handshake.get("X-Internal-Token"));
        assertEquals(String.valueOf(userId(username)), handshake.get("X-Kcr-User-Id"));
        assertEquals("OPERATOR", handshake.get("X-Kcr-User-Role"));
        assertEquals("/api/v1/ai/chat", ml.takeRequest().getPath());
    }

    @Test
    void ticketIsAcceptedOnlyOnce() throws Exception {
        String ticket = issueTicket(registerAndLogin("ai-once-" + suffix()));
        echoChat();

        WebSocket first = openChat("ticket=" + ticket, null, new ChatClient())
                .get(WAIT.toSeconds(), TimeUnit.SECONDS);
        first.abort();
        assertNotNull(mlHandshakes.poll(WAIT.toSeconds(), TimeUnit.SECONDS));

        assertEquals(401, handshakeStatus("ticket=" + ticket));
        assertNull(mlHandshakes.poll(1, TimeUnit.SECONDS));
    }

    @Test
    void onlyAValidTicketOpensTheChat() throws Exception {
        String accessToken = registerAndLogin("ai-bad-" + suffix());

        assertEquals(401, handshakeStatus("ticket=" + "A".repeat(43)));
        assertEquals(401, handshakeStatus("ticket=not-a-ticket"));
        assertEquals(401, handshakeStatus("token=" + accessToken));
        assertEquals(401, handshakeStatus(""));
        assertTrue(mlHandshakes.isEmpty());
    }

    @Test
    void mlRefusalAndOutageRefuseTheHandshake() throws Exception {
        String accessToken = registerAndLogin("ai-down-" + suffix());
        String ticket = issueTicket(accessToken);

        mlBehaviour.set(request -> refusedConnection().setResponseCode(FORBIDDEN));
        assertEquals(503, handshakeStatus("ticket=" + ticket));

        mlBehaviour.set(request -> new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        assertEquals(503, handshakeStatus("ticket=" + issueTicket(accessToken)));
        assertTrue(mlHandshakes.isEmpty());
    }

    @Test
    void handshakeOriginIsLimitedToConfiguredOrigins() throws Exception {
        String accessToken = registerAndLogin("ai-org-" + suffix());
        echoChat();

        assertEquals(FORBIDDEN, handshakeStatus("ticket=" + issueTicket(accessToken), "http://evil.example"));
        assertNull(mlHandshakes.poll(1, TimeUnit.SECONDS));

        WebSocket chat = openChat("ticket=" + issueTicket(accessToken), ALLOWED_ORIGIN, new ChatClient())
                .get(WAIT.toSeconds(), TimeUnit.SECONDS);
        chat.abort();
        assertNotNull(mlHandshakes.poll(WAIT.toSeconds(), TimeUnit.SECONDS));
    }

    private void echoChat() {
        mlBehaviour.set(request -> new MockResponse().withWebSocketUpgrade(new okhttp3.WebSocketListener() {
            @Override
            public void onOpen(okhttp3.WebSocket webSocket, Response response) {
                openChatSockets.add(webSocket);
                mlHandshakes.add(response.request().headers());
            }

            @Override
            public void onClosed(okhttp3.WebSocket webSocket, int code, String reason) {
                openChatSockets.remove(webSocket);
            }

            @Override
            public void onFailure(okhttp3.WebSocket webSocket, Throwable t, Response response) {
                openChatSockets.remove(webSocket);
            }

            @Override
            public void onMessage(okhttp3.WebSocket webSocket, String text) {
                if ("close-me".equals(text)) {
                    webSocket.close(POLICY_VIOLATION, "policy");
                } else {
                    webSocket.send("echo:" + text);
                }
            }
        }));
    }

    private static MockResponse refusedConnection() {
        return new MockResponse().setHeader("Connection", "close");
    }

    private int handshakeStatus(String query) throws Exception {
        return handshakeStatus(query, null);
    }

    private int handshakeStatus(String query, String origin) throws Exception {
        try {
            openChat(query, origin, new ChatClient()).get(WAIT.toSeconds(), TimeUnit.SECONDS);
            return SWITCHING_PROTOCOLS;
        } catch (ExecutionException e) {
            return ((WebSocketHandshakeException) e.getCause()).getResponse().statusCode();
        }
    }

    private CompletableFuture<WebSocket> openChat(String query, String origin, WebSocket.Listener listener) {
        WebSocket.Builder builder = http.newWebSocketBuilder().connectTimeout(WAIT);
        if (origin != null) {
            builder.header("Origin", origin);
        }
        return builder.buildAsync(URI.create("ws://localhost:" + port() + "/api/v1/ai/chat?" + query), listener);
    }

    private int port() {
        return environment.getProperty("local.server.port", Integer.class);
    }

    private static String internalToken() {
        return System.getProperty("ML_INTERNAL_TOKEN");
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private long userId(String username) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE username = ?", Long.class, username);
    }

    private String registerAndLogin(String username) throws Exception {
        Map<String, Object> credentials = Map.of("username", username, "password", "password123");
        assertEquals(201, post("/api/v1/auth/register", null, credentials).statusCode());
        JsonNode login = JSON.readTree(post("/api/v1/auth/login", null, credentials).body());
        return login.get("accessToken").asString();
    }

    private String issueTicket(String accessToken) throws Exception {
        JsonNode issued = JSON.readTree(post("/api/v1/ai/chat/ticket", accessToken, null).body());
        return issued.get("ticket").asString();
    }

    private HttpResponse<String> post(String path, String accessToken, Map<String, Object> body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port() + path))
                .header("Content-Type", "application/json")
                .POST(body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (accessToken != null) {
            request.header("Authorization", "Bearer " + accessToken);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static final class ChatClient implements WebSocket.Listener {

        private final StringBuilder frame = new StringBuilder();
        private final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closeCode = new CompletableFuture<>();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            frame.append(data);
            if (last) {
                frames.add(frame.toString());
                frame.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closeCode.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closeCode.completeExceptionally(error);
        }
    }
}

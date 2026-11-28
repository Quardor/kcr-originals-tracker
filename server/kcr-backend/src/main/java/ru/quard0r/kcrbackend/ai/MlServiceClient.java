package ru.quard0r.kcrbackend.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import ru.quard0r.kcrbackend.ai.dto.AiProxyRequest;
import ru.quard0r.kcrbackend.ai.dto.AiProxyResponse;
import ru.quard0r.kcrbackend.common.error.DomainException;
import ru.quard0r.kcrbackend.common.error.ErrorCode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Component
@Slf4j
public class MlServiceClient {

    static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    static final String USER_ID_HEADER = "X-Kcr-User-Id";
    static final String USER_ROLE_HEADER = "X-Kcr-User-Role";
    static final String CHAT_PATH = "/api/v1/ai/chat";
    static final Duration CHAT_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private static final String API_PREFIX = "/api/v1/ai";
    private static final int INTERNAL_TOKEN_MIN_BYTES = 32;

    private final RestClient restClient;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String internalToken;

    public MlServiceClient(RestClient mlRestClient,
                           HttpClient mlHttpClient,
                           @Value("${app.ml.base-url}") String baseUrl,
                           @Value("${app.ml.internal-token}") String internalToken) {
        if (internalToken.getBytes(StandardCharsets.UTF_8).length < INTERNAL_TOKEN_MIN_BYTES) {
            throw new IllegalStateException("app.ml.internal-token must be at least 32 bytes long");
        }
        this.restClient = mlRestClient;
        this.httpClient = mlHttpClient;
        this.baseUrl = StringUtils.trimTrailingCharacter(baseUrl, '/');
        this.internalToken = internalToken;
    }

    public AiProxyResponse exchange(AiProxyRequest request) {
        RestClient.RequestBodySpec outbound = restClient
                .method(HttpMethod.valueOf(request.method()))
                .uri(buildUri(request))
                .headers(headers -> applyIdentityHeaders(headers, request));
        RestClient.RequestBodySpec prepared = request.body() == null
                ? outbound
                : outbound.body(request.body());
        try {
            return prepared.exchange((mlRequest, mlResponse) -> AiProxyResponse.builder()
                    .status(mlResponse.getStatusCode().value())
                    .contentType(mediaTypeAsString(mlResponse.getHeaders().getContentType()))
                    .contentDisposition(mlResponse.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                    .body(mlResponse.getBody().readAllBytes())
                    .build());
        } catch (ResourceAccessException e) {
            log.warn("kcr-ml is unreachable for {} {}: {}", request.method(), request.path(), e.getMessage());
            throw new DomainException(ErrorCode.AI_SERVICE_UNAVAILABLE);
        }
    }

    public CompletableFuture<WebSocket> openChat(long userId, String userRole, String accessToken,
                                                 WebSocket.Listener listener) {
        return httpClient.newWebSocketBuilder()
                .connectTimeout(CHAT_CONNECT_TIMEOUT)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .header(INTERNAL_TOKEN_HEADER, internalToken)
                .header(USER_ID_HEADER, Long.toString(userId))
                .header(USER_ROLE_HEADER, userRole)
                .buildAsync(URI.create(baseUrl.replaceFirst("^http", "ws") + CHAT_PATH), listener);
    }

    private URI buildUri(AiProxyRequest request) {
        StringBuilder uri = new StringBuilder(baseUrl).append(API_PREFIX).append(request.path());
        String query = request.queryParams().entrySet().stream()
                .flatMap(entry -> entry.getValue().stream()
                        .map(value -> encode(entry.getKey()) + "=" + encode(value)))
                .collect(Collectors.joining("&"));
        if (!query.isEmpty()) {
            uri.append('?').append(query);
        }
        return URI.create(uri.toString());
    }

    private void applyIdentityHeaders(HttpHeaders headers, AiProxyRequest request) {
        headers.set(INTERNAL_TOKEN_HEADER, internalToken);
        headers.set(USER_ID_HEADER, Long.toString(request.callerId()));
        headers.set(USER_ROLE_HEADER, request.callerRole());
        headers.set(HttpHeaders.AUTHORIZATION, request.authorization());
        if (request.contentType() != null) {
            headers.setContentType(MediaType.parseMediaType(request.contentType()));
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String mediaTypeAsString(MediaType mediaType) {
        return mediaType == null ? null : mediaType.toString();
    }
}

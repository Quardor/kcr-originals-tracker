package ru.quard0r.kcrbackend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import ru.quard0r.kcrbackend.ai.AiChatHandshakeInterceptor;
import ru.quard0r.kcrbackend.ai.AiChatRelayHandler;

import java.util.List;

@Configuration
@EnableWebSocket
public class AiWebSocketConfig implements WebSocketConfigurer {

    private final AiChatRelayHandler aiChatRelayHandler;
    private final AiChatHandshakeInterceptor aiChatHandshakeInterceptor;
    private final List<String> allowedOrigins;

    public AiWebSocketConfig(AiChatRelayHandler aiChatRelayHandler,
                             AiChatHandshakeInterceptor aiChatHandshakeInterceptor,
                             @Value("${app.cors.allowed-origins:}") List<String> allowedOrigins) {
        this.aiChatRelayHandler = aiChatRelayHandler;
        this.aiChatHandshakeInterceptor = aiChatHandshakeInterceptor;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        WebSocketHandlerRegistration registration = registry
                .addHandler(aiChatRelayHandler, AiChatRelayHandler.PATH)
                .addInterceptors(aiChatHandshakeInterceptor);
        List<String> origins = allowedOrigins.stream()
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();
        if (!origins.isEmpty()) {
            registration.setAllowedOrigins(origins.toArray(String[]::new));
        }
    }
}

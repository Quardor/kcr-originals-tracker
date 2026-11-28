package ru.quard0r.kcrbackend.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;
import ru.quard0r.kcrbackend.ai.dto.ChatTicketResponse;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
public class ChatTicketService {

    static final long TICKET_LIFETIME_SECONDS = 30;

    private static final int TICKET_ENTROPY_BYTES = 32;
    private static final long TICKET_CACHE_MAX_SIZE = 10_000;
    private static final Pattern TICKET_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Cache<String, ChatTicket> tickets = Caffeine.newBuilder()
            .maximumSize(TICKET_CACHE_MAX_SIZE)
            .expireAfterWrite(Duration.ofSeconds(TICKET_LIFETIME_SECONDS))
            .build();

    private final Clock clock;

    public ChatTicketService() {
        this(Clock.systemUTC());
    }

    ChatTicketService(Clock clock) {
        this.clock = clock;
    }

    public ChatTicketResponse issue(String accessToken) {
        byte[] entropy = new byte[TICKET_ENTROPY_BYTES];
        RANDOM.nextBytes(entropy);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        Instant issuedAt = clock.instant();
        tickets.put(ticket, new ChatTicket(accessToken, issuedAt.plusSeconds(TICKET_LIFETIME_SECONDS)));
        return ChatTicketResponse.builder()
                .ticket(ticket)
                .expiresInSeconds(TICKET_LIFETIME_SECONDS)
                .build();
    }

    public Optional<String> consume(String ticket) {
        if (ticket == null || !TICKET_PATTERN.matcher(ticket).matches()) {
            return Optional.empty();
        }
        ChatTicket issued = tickets.asMap().remove(ticket);
        if (issued == null || !clock.instant().isBefore(issued.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(issued.accessToken());
    }
}

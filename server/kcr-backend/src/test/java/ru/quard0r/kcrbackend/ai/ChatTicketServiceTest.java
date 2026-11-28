package ru.quard0r.kcrbackend.ai;

import org.junit.jupiter.api.Test;
import ru.quard0r.kcrbackend.ai.dto.ChatTicketResponse;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatTicketServiceTest {

    private static final Instant ISSUED_AT = Instant.parse("2026-11-28T12:00:00Z");
    private static final String ACCESS_TOKEN = "access-token";

    @Test
    void issuedTicketReturnsTheBoundAccessTokenOnce() {
        ChatTicketService service = new ChatTicketService(Clock.fixed(ISSUED_AT, ZoneOffset.UTC));
        ChatTicketResponse issued = service.issue(ACCESS_TOKEN);

        assertEquals(30, issued.expiresInSeconds());
        assertTrue(issued.ticket().matches("[A-Za-z0-9_-]{43}"));
        assertEquals(ACCESS_TOKEN, service.consume(issued.ticket()).orElseThrow());
        assertTrue(service.consume(issued.ticket()).isEmpty());
    }

    @Test
    void ticketsAreUniqueAndBoundToTheirOwnAccessToken() {
        ChatTicketService service = new ChatTicketService(Clock.fixed(ISSUED_AT, ZoneOffset.UTC));
        String firstTicket = service.issue("first-token").ticket();
        String secondTicket = service.issue("second-token").ticket();

        assertNotEquals(firstTicket, secondTicket);
        assertEquals("second-token", service.consume(secondTicket).orElseThrow());
        assertEquals("first-token", service.consume(firstTicket).orElseThrow());
    }

    @Test
    void consumeRejectsUnknownAndMalformedTickets() {
        ChatTicketService service = new ChatTicketService(Clock.fixed(ISSUED_AT, ZoneOffset.UTC));

        assertTrue(service.consume(null).isEmpty());
        assertTrue(service.consume("").isEmpty());
        assertTrue(service.consume("not-a-ticket").isEmpty());
        assertTrue(service.consume("A".repeat(43)).isEmpty());
        assertTrue(service.consume(service.issue(ACCESS_TOKEN).ticket() + "A").isEmpty());
    }

    @Test
    void ticketIsLiveUntilItsLifetimeEnds() {
        MutableClock clock = new MutableClock(ISSUED_AT);
        ChatTicketService service = new ChatTicketService(clock);
        String ticket = service.issue(ACCESS_TOKEN).ticket();

        clock.advance(Duration.ofSeconds(29));
        assertEquals(ACCESS_TOKEN, service.consume(ticket).orElseThrow());

        String expiringTicket = service.issue(ACCESS_TOKEN).ticket();
        clock.advance(Duration.ofSeconds(30));
        assertTrue(service.consume(expiringTicket).isEmpty());
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}

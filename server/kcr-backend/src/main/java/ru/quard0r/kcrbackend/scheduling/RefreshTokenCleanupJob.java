package ru.quard0r.kcrbackend.scheduling;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import ru.quard0r.kcrbackend.service.RefreshTokenService;

@Component
@RequiredArgsConstructor
public class RefreshTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);

    private final RefreshTokenService refreshTokenService;

    @Scheduled(cron = "${app.cleanup.refresh-token-cron}")
    public void cleanupExpiredRefreshTokens() {
        long deleted = refreshTokenService.deleteExpired();
        if (deleted > 0) {
            log.info("Deleted {} expired refresh token(s)", deleted);
        }
    }
}

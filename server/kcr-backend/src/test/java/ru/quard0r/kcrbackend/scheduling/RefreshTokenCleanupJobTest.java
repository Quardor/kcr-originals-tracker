package ru.quard0r.kcrbackend.scheduling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.quard0r.kcrbackend.service.RefreshTokenService;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RefreshTokenCleanupJobTest {

    @Mock
    private RefreshTokenService refreshTokenService;

    @InjectMocks
    private RefreshTokenCleanupJob refreshTokenCleanupJob;

    @Test
    void cleanupDeletesExpiredTokensThroughService() {
        when(refreshTokenService.deleteExpired()).thenReturn(5L);

        refreshTokenCleanupJob.cleanupExpiredRefreshTokens();

        verify(refreshTokenService).deleteExpired();
    }

    @Test
    void cleanupSurvivesNothingDeleted() {
        when(refreshTokenService.deleteExpired()).thenReturn(0L);

        assertDoesNotThrow(() -> refreshTokenCleanupJob.cleanupExpiredRefreshTokens());

        verify(refreshTokenService).deleteExpired();
    }
}

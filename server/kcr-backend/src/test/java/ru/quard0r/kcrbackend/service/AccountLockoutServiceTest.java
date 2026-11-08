package ru.quard0r.kcrbackend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import ru.quard0r.kcrbackend.users.model.Role;
import ru.quard0r.kcrbackend.users.model.User;
import ru.quard0r.kcrbackend.users.repository.UserRepository;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class AccountLockoutServiceTest {

    private static final int MAX_FAILED_ATTEMPTS = 5;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AccountLockoutService accountLockoutService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(accountLockoutService, "maxFailedAttempts", MAX_FAILED_ATTEMPTS);
        ReflectionTestUtils.setField(accountLockoutService, "lockDuration", Duration.ofMinutes(15));
    }

    private User user(int failedAttempts) {
        return User.builder()
                .username("user")
                .password("$2a$10$hashedpassword")
                .role(Role.OPERATOR)
                .failedAttempts(failedAttempts)
                .build();
    }

    @Test
    void registerFailedLoginDelegatesToAtomicBulkUpdateWithLockDeadline() {
        // Решение о блокировке живёт в SQL (case при достижении порога); сервис передаёт
        // порог и срок и больше не делает read-modify-write над сущностью.
        accountLockoutService.registerFailedLogin("user");

        ArgumentCaptor<Instant> deadline = ArgumentCaptor.forClass(Instant.class);
        verify(userRepository).registerFailedLogin(eq("user"), eq(MAX_FAILED_ATTEMPTS), deadline.capture());
        Instant expected = Instant.now().plus(Duration.ofMinutes(15));
        assertTrue(!deadline.getValue().isBefore(expected.minusSeconds(5))
                && !deadline.getValue().isAfter(expected.plusSeconds(5)));
        verifyNoMoreInteractions(userRepository);
    }

    @Test
    void registerFailedLoginPassesUsernameVerbatim() {
        // Неизвестный username: bulk-UPDATE не находит строку — no-op на уровне SQL.
        accountLockoutService.registerFailedLogin("ghost");

        verify(userRepository).registerFailedLogin(eq("ghost"), eq(MAX_FAILED_ATTEMPTS),
                ArgumentCaptor.forClass(Instant.class).capture());
        verifyNoMoreInteractions(userRepository);
    }

    @Test
    void resetFailedLoginsDelegatesConditionalBulkUpdateById() {
        User user = user(2);

        accountLockoutService.resetFailedLogins(user);

        verify(userRepository).resetFailedLogins(user.getId());
        // Сущность не мутируется и не сохраняется: сброс выполняется одним UPDATE.
        assertEquals(2, user.getFailedAttempts());
        verifyNoMoreInteractions(userRepository);
    }
}

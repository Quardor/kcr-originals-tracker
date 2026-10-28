package ru.quard0r.kcrbackend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import ru.quard0r.kcrbackend.model.Role;
import ru.quard0r.kcrbackend.model.User;
import ru.quard0r.kcrbackend.repository.UserRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountLockoutServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private AccountLockoutService accountLockoutService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(accountLockoutService, "maxFailedAttempts", 5);
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
    void registerFailedLoginIncrementsCounterWithoutLocking() {
        when(userRepository.findByUsername("user")).thenReturn(Optional.of(user(0)));

        accountLockoutService.registerFailedLogin("user");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals(1, captor.getValue().getFailedAttempts());
        assertNull(captor.getValue().getLockedUntil());
    }

    @Test
    void registerFailedLoginLocksAccountAtThreshold() {
        when(userRepository.findByUsername("user")).thenReturn(Optional.of(user(4)));

        accountLockoutService.registerFailedLogin("user");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertEquals(5, captor.getValue().getFailedAttempts());
        assertNotNull(captor.getValue().getLockedUntil());
        assertTrue(captor.getValue().getLockedUntil().isAfter(Instant.now()));
    }

    @Test
    void registerFailedLoginIgnoresUnknownUsername() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        accountLockoutService.registerFailedLogin("ghost");

        verify(userRepository, never()).save(any());
    }

    @Test
    void resetFailedLoginsClearsCounterAndLock() {
        User user = User.builder()
                .username("user")
                .password("$2a$10$hashedpassword")
                .role(Role.OPERATOR)
                .failedAttempts(2)
                .lockedUntil(Instant.now().plusSeconds(60))
                .build();

        accountLockoutService.resetFailedLogins(user);

        assertEquals(0, user.getFailedAttempts());
        assertNull(user.getLockedUntil());
        verify(userRepository).save(user);
    }

    @Test
    void resetFailedLoginsSkipsAlreadyCleanAccount() {
        accountLockoutService.resetFailedLogins(user(0));

        verify(userRepository, never()).save(any());
    }
}

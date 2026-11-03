package ru.quard0r.kcrbackend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.quard0r.kcrbackend.model.User;
import ru.quard0r.kcrbackend.repository.UserRepository;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class AccountLockoutService {

    private final UserRepository userRepository;

    @Value("${app.security.account-lockout.max-failed-attempts:5}")
    private int maxFailedAttempts;

    @Value("${app.security.account-lockout.lock-duration:15m}")
    private Duration lockDuration;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registerFailedLogin(String username) {
        userRepository.registerFailedLogin(username, maxFailedAttempts,
                Instant.now().plus(lockDuration));
    }

    public void resetFailedLogins(User user) {
        userRepository.resetFailedLogins(user.getId());
    }
}

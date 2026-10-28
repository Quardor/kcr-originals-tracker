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
        userRepository.findByUsername(username).ifPresent(user -> {
            int attempts = user.getFailedAttempts() + 1;
            user.setFailedAttempts(attempts);
            if (attempts >= maxFailedAttempts) {
                user.setLockedUntil(Instant.now().plus(lockDuration));
            }
            userRepository.save(user);
        });
    }

    public void resetFailedLogins(User user) {
        if (user.getFailedAttempts() > 0 || user.getLockedUntil() != null) {
            user.setFailedAttempts(0);
            user.setLockedUntil(null);
            userRepository.save(user);
        }
    }
}

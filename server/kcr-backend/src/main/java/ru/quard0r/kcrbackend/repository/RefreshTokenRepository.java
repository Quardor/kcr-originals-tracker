package ru.quard0r.kcrbackend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import ru.quard0r.kcrbackend.model.RefreshToken;
import ru.quard0r.kcrbackend.model.User;

import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByToken(String token);
    
    @Modifying
    void deleteByUser(User user);

    @Modifying
    void deleteByToken(String token);
}

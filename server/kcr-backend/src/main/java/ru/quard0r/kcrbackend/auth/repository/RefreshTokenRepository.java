package ru.quard0r.kcrbackend.auth.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.quard0r.kcrbackend.auth.model.RefreshToken;
import ru.quard0r.kcrbackend.users.model.User;

import java.time.Instant;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    // SELECT … FOR UPDATE serializes concurrent rotation of the same token (§10, item 8).
    // Deliberately without join fetch: FOR UPDATE over a join would also lock the users row,
    // which deadlocks with deleteByUser when two tokens of one user rotate in parallel.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    @Modifying
    void deleteByUser(User user);

    @Modifying
    void deleteByTokenHash(String tokenHash);

    @Modifying
    long deleteByExpiryDateBefore(Instant expiryDateBefore);
}

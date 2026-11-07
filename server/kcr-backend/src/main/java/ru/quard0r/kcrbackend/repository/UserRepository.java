package ru.quard0r.kcrbackend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.quard0r.kcrbackend.model.Role;
import ru.quard0r.kcrbackend.model.User;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    List<User> findAllByOrderByUsernameAsc();

    boolean existsByRole(Role role);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    @Modifying
    @Query("update User u set u.failedAttempts = u.failedAttempts + 1, "
            + "u.lockedUntil = case when u.failedAttempts + 1 >= :maxFailedAttempts "
            + "then :lockedUntil else u.lockedUntil end "
            + "where u.username = :username")
    int registerFailedLogin(@Param("username") String username,
                            @Param("maxFailedAttempts") int maxFailedAttempts,
                            @Param("lockedUntil") Instant lockedUntil);

    @Modifying
    @Query("update User u set u.failedAttempts = 0, u.lockedUntil = null "
            + "where u.id = :id and (u.failedAttempts <> 0 or u.lockedUntil is not null)")
    int resetFailedLogins(@Param("id") Long id);
}

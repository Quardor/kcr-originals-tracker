package ru.quard0r.kcrbackend.clients.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.quard0r.kcrbackend.clients.model.Client;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ClientRepository extends JpaRepository<Client, Long>, JpaSpecificationExecutor<Client> {

    Optional<Client> findByKcrCode(String kcrCode);

    boolean existsByKcrCode(String kcrCode);

    List<Client> findByKcrCodeIn(Collection<String> kcrCodes);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Client c SET c.assignee.id = :userId WHERE c.id = :clientId AND c.assignee IS NULL")
    int assignAssigneeIfFree(@Param("clientId") Long clientId, @Param("userId") Long userId);
}

package ru.quard0r.kcrbackend.clients.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import ru.quard0r.kcrbackend.clients.model.Client;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ClientRepository extends JpaRepository<Client, Long>, JpaSpecificationExecutor<Client> {

    Optional<Client> findByKcrCode(String kcrCode);

    boolean existsByKcrCode(String kcrCode);

    List<Client> findByKcrCodeIn(Collection<String> kcrCodes);
}

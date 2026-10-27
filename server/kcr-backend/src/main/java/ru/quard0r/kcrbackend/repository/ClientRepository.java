package ru.quard0r.kcrbackend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import ru.quard0r.kcrbackend.model.Client;

import java.util.Optional;

public interface ClientRepository extends JpaRepository<Client, Long>, JpaSpecificationExecutor<Client> {

    Optional<Client> findByKcrCode(String kcrCode);

    boolean existsByKcrCode(String kcrCode);
}

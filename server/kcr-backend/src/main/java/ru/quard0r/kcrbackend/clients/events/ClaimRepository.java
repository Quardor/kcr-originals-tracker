package ru.quard0r.kcrbackend.clients.events;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    List<Claim> findByClientIdOrderByIdAsc(Long clientId);
}

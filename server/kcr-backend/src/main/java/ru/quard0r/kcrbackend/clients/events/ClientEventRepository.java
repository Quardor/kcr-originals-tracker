package ru.quard0r.kcrbackend.clients.events;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClientEventRepository extends JpaRepository<ClientEvent, Long> {

    Page<ClientEvent> findByClientId(Long clientId, Pageable pageable);
}

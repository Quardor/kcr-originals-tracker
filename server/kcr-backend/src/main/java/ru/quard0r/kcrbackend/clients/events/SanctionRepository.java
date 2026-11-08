package ru.quard0r.kcrbackend.clients.events;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SanctionRepository extends JpaRepository<Sanction, Long> {

    List<Sanction> findByClaimIdInOrderByIdAsc(Collection<Long> claimIds);
}

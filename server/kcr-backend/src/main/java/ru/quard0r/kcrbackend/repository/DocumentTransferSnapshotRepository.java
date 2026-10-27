package ru.quard0r.kcrbackend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.quard0r.kcrbackend.model.DocumentTransferSnapshot;

import java.util.List;
import java.util.Optional;

public interface DocumentTransferSnapshotRepository extends JpaRepository<DocumentTransferSnapshot, Long> {

    List<DocumentTransferSnapshot> findByClientIdOrderBySnapshotDateAscIdAsc(Long clientId);

    List<DocumentTransferSnapshot> findByClientIdAndReportingPeriodOrderBySnapshotDateAscIdAsc(
            Long clientId, String reportingPeriod);

    Optional<DocumentTransferSnapshot> findFirstByClientIdAndReportingPeriodOrderBySnapshotDateDescIdDesc(
            Long clientId, String reportingPeriod);
}

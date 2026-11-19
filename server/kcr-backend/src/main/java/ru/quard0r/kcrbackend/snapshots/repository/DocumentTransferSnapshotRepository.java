package ru.quard0r.kcrbackend.snapshots.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ru.quard0r.kcrbackend.snapshots.model.DocumentTransferSnapshot;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DocumentTransferSnapshotRepository extends JpaRepository<DocumentTransferSnapshot, Long>
{
    List<DocumentTransferSnapshot> findByClientIdOrderBySnapshotDateAscIdAsc(Long clientId);

    List<DocumentTransferSnapshot> findByClientIdAndReportingPeriodOrderBySnapshotDateAscIdAsc(
            Long clientId, String reportingPeriod);

    Optional<DocumentTransferSnapshot> findFirstByClientIdAndReportingPeriodOrderBySnapshotDateDescIdDesc(
            Long clientId, String reportingPeriod);

    /**
     * Загрузить срезы только для конкретного набора клиентов (избегает findAll по всей таблице).
     * Используется в WorklistService для предпросмотра и получения закреплённого списка.
     */
    @Query("SELECT s FROM DocumentTransferSnapshot s WHERE s.client.id IN :clientIds")
    List<DocumentTransferSnapshot> findByClientIdIn(@Param("clientIds") Collection<Long> clientIds);
}

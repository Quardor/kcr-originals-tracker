package ru.quard0r.kcrbackend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "document_transfer_snapshots", indexes = @Index(
        name = "idx_document_transfer_snapshots_client_period_date",
        columnList = "client_id, reporting_period, snapshot_date"))
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
public class DocumentTransferSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Column(nullable = false)
    private String reportingPeriod;

    @Column(nullable = false)
    private LocalDate snapshotDate;

    private Integer totalDocuments;

    @Column(nullable = false)
    private Integer notTransferredDocuments;
}

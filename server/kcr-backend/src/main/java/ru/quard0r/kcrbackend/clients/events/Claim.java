package ru.quard0r.kcrbackend.clients.events;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import ru.quard0r.kcrbackend.clients.model.Client;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Претензия к клиенту (§11.3): добавляется администратором, изменения статуса
 * дублируются событием CLAIM в общей истории клиента. Номер — внешний номер
 * претензии, иммутабелен после создания.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "claims", indexes = @Index(name = "idx_claims_client_id", columnList = "client_id"))
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
public class Claim {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Column(nullable = false, length = 64)
    private String number;

    @Column(name = "claim_date", nullable = false)
    private LocalDate claimDate;

    @Column(name = "incident_number", length = 64)
    private String incidentNumber;

    @Column(name = "received_by_client_at")
    private LocalDate receivedByClientAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ClaimStatus status = ClaimStatus.NEW;

    @Column(columnDefinition = "text")
    private String comment;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

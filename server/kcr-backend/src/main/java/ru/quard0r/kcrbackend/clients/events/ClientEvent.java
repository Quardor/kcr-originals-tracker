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
import ru.quard0r.kcrbackend.users.model.User;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Запись единой истории клиента с датой, временем и автором (§11.3): звонок с
 * результатом и договорённостью, письмо, комментарий либо системная запись о
 * мутации. Поля call_result/agreement/client_deadline/next_contact_at/record_url
 * заполняются только для CALL (валидация — сервисный слой).
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "client_events", indexes = @Index(
        name = "idx_client_events_client_created",
        columnList = "client_id, created_at, id"))
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
public class ClientEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClientEventType type;

    @Column(columnDefinition = "text")
    private String comment;

    @Enumerated(EnumType.STRING)
    private CallResult callResult;

    @Column(columnDefinition = "text")
    private String agreement;

    @Column(name = "client_deadline")
    private LocalDate clientDeadline;

    @Column(name = "next_contact_at")
    private LocalDate nextContactAt;

    @Column(name = "record_url", columnDefinition = "text")
    private String recordUrl;
}

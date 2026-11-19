package ru.quard0r.kcrbackend.clients.model;

import jakarta.persistence.*;
import lombok.*;
import ru.quard0r.kcrbackend.users.model.User;

import java.time.Instant;


@Entity
@Table(name = "call_list_items", indexes = {
        @Index(name = "idx_call_list_items_position", columnList = "position"),
        @Index(name = "idx_call_list_items_created_at", columnList = "created_at")
})
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
public class CallListItem
{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false, unique = true)
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id", nullable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "position", nullable = false)
    private Integer position;
}

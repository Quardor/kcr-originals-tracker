package ru.quard0r.kcrbackend.auth.model;

import ru.quard0r.kcrbackend.users.model.User;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "refresh_tokens", indexes = @Index(name = "idx_refresh_tokens_user_id", columnList = "user_id"))
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(nullable = false, unique = true)
    private String tokenHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "expiry_date", nullable = false)
    private Instant expiryDate;

    @Column(name = "rotated_date")
    private Instant rotatedDate;
}

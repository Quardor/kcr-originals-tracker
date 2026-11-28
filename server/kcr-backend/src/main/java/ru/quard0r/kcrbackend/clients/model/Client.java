package ru.quard0r.kcrbackend.clients.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import ru.quard0r.kcrbackend.users.model.User;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "clients")
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "clients_seq")
    @SequenceGenerator(name = "clients_seq", sequenceName = "clients_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false, unique = true)
    private String kcrCode;

    @Column(nullable = false, length = 12)
    private String inn;

    @Column(length = 9)
    private String kpp;

    @Column(nullable = false)
    private String organizationName;

    private String internalId;

    @Enumerated(EnumType.STRING)
    private ClientType clientType;

    private Boolean kcrActive;

    private Boolean kcrBlocked;

    private String tariff;

    private String ekbTravelTime;

    private String manager;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "varchar(255)[]")
    private List<String> flags;

    private String contactPerson;

    private String contactPhone;

    private String contactEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private WorkStage stage = WorkStage.NOT_IN_WORK;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    @Column(name = "next_contact_at")
    private LocalDate nextContactAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

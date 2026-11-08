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
    @GeneratedValue(strategy = GenerationType.IDENTITY)
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

    /** Этап работы — отдельно от состояния передачи документов (§11.3). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private WorkStage stage = WorkStage.NOT_IN_WORK;

    /** Ответственный оператор; null — не взят в работу (§11.3). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignee_id")
    private User assignee;

    /** Дата следующего контакта — обновляется событием звонка (§11.2). */
    @Column(name = "next_contact_at")
    private LocalDate nextContactAt;

    /** Появление в сервисе — база правила «новичок < 6 месяцев» (§11.2). */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}

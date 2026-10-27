package ru.quard0r.kcrbackend.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

@Entity
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
}

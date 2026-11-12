package ru.quard0r.kcrbackend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Аудит-поля создания (@CreatedDate): clients.created_at, client_events/claims/
 * sanctions.created_at (п. 24 §10, шаг «Операторский цикл» §11.8).
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}

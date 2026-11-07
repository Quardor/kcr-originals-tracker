-- Поля операторского цикла на клиенте (ARCHITECTURE.md §11.3, «Доработка clients»):
-- stage — этап работы, хранится отдельно от состояния передачи документов (прямое
--   требование ТЗ); по умолчанию клиент не в работе;
-- assignee_id — ответственный оператор (null = не взят в работу);
-- next_contact_at — дата следующего контакта, обновляется событием звонка;
-- created_at — появление в сервисе, база правила «новичок < 6 месяцев» (§11.2);
--   существующие строки получают момент применения миграции.

ALTER TABLE clients
    ADD COLUMN stage           VARCHAR(32) NOT NULL DEFAULT 'NOT_IN_WORK',
    ADD COLUMN assignee_id     BIGINT,
    ADD COLUMN next_contact_at DATE,
    ADD COLUMN created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    ADD CONSTRAINT clients_stage_check
        CHECK (stage IN ('NOT_IN_WORK', 'IN_POOL', 'FIRST_CALL', 'WAITING_CLIENT_DEADLINE',
                         'RECHECK', 'SECOND_CALL', 'CLAIM_WORK', 'COMPLETED')),
    ADD CONSTRAINT fk_clients_assignee_users
        FOREIGN KEY (assignee_id) REFERENCES users (id);

-- «сегодня нужно»: next_contact_at <= сегодня (частичный индекс — пустые даты не хранит)
CREATE INDEX idx_clients_next_contact_at
    ON clients (next_contact_at)
    WHERE next_contact_at IS NOT NULL;

-- список «мои клиенты» / переназначения; FK не индексируется PostgreSQL автоматически
CREATE INDEX idx_clients_assignee_id
    ON clients (assignee_id);

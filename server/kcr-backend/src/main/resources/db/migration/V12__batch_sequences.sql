-- V12: Переключение clients, client_events и document_transfer_snapshots
-- с IDENTITY на явные sequence-ы, чтобы Hibernate мог батчить INSERT-ы
-- (IDENTITY вынуждает flush после каждой строки).
-- allocationSize = 50 соответствует batch_size в application.yaml.

CREATE SEQUENCE clients_seq
    START WITH 1
    MINVALUE 0
    INCREMENT BY 50
    NO CYCLE;

-- Инициализируем sequence так, чтобы следующее значение было выше текущего max id,
-- чтобы не было конфликта с уже существующими строками.
SELECT setval('clients_seq', COALESCE((SELECT MAX(id) FROM clients), 0));

CREATE SEQUENCE client_events_seq
    START WITH 1
    MINVALUE 0
    INCREMENT BY 50
    NO CYCLE;

SELECT setval('client_events_seq', COALESCE((SELECT MAX(id) FROM client_events), 0));

CREATE SEQUENCE document_transfer_snapshots_seq
    START WITH 1
    MINVALUE 0
    INCREMENT BY 50
    NO CYCLE;

SELECT setval('document_transfer_snapshots_seq',
              COALESCE((SELECT MAX(id) FROM document_transfer_snapshots), 0));

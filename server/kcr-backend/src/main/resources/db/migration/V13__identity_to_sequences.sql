ALTER SEQUENCE clients_seq MINVALUE 0 OWNED BY clients.id;

ALTER TABLE clients
    ALTER COLUMN id DROP IDENTITY,
    ALTER COLUMN id SET DEFAULT nextval('clients_seq'::regclass);

SELECT setval('clients_seq', GREATEST(
    (SELECT COALESCE(MAX(id), 0) FROM clients),
    (SELECT last_value FROM clients_seq)));

ALTER SEQUENCE client_events_seq MINVALUE 0 OWNED BY client_events.id;

ALTER TABLE client_events
    ALTER COLUMN id DROP IDENTITY,
    ALTER COLUMN id SET DEFAULT nextval('client_events_seq'::regclass);

SELECT setval('client_events_seq', GREATEST(
    (SELECT COALESCE(MAX(id), 0) FROM client_events),
    (SELECT last_value FROM client_events_seq)));

ALTER SEQUENCE document_transfer_snapshots_seq MINVALUE 0 OWNED BY document_transfer_snapshots.id;

ALTER TABLE document_transfer_snapshots
    ALTER COLUMN id DROP IDENTITY,
    ALTER COLUMN id SET DEFAULT nextval('document_transfer_snapshots_seq'::regclass);

SELECT setval('document_transfer_snapshots_seq', GREATEST(
    (SELECT COALESCE(MAX(id), 0) FROM document_transfer_snapshots),
    (SELECT last_value FROM document_transfer_snapshots_seq)));

-- Case-insensitive uniqueness of username and email (item 30 of ARCHITECTURE.md §10):
-- exact-value unique constraints are replaced by functional unique indexes on lower(...).
-- The application normalizes both fields to lowercase on write and lookup
-- (common/UserIdentityNormalizer), so stored values are already lowercase; existing
-- rows are assumed case-consistent (dev/test data only — a mixed-case duplicate pair
-- would fail index creation loudly, which is the intended behaviour).
-- citext is deliberately not used: it requires CREATE EXTENSION privileges.

ALTER TABLE users DROP CONSTRAINT uk_users_username;
ALTER TABLE users DROP CONSTRAINT uk_users_email;

CREATE UNIQUE INDEX idx_users_username_lower ON users (lower(username));
CREATE UNIQUE INDEX idx_users_email_lower    ON users (lower(email));

-- Account lockout after repeated failed logins (brute-force protection, review item 12).
ALTER TABLE users
    ADD COLUMN failed_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN locked_until    TIMESTAMP(6) WITH TIME ZONE;

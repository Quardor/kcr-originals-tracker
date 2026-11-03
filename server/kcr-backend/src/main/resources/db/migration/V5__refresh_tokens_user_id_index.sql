-- Review item 9 (§10): Postgres does not index FK columns automatically, so
-- deleteByUser (token family revocation) was doing a sequential scan.

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);

-- Review item 3 (PROJECT_CONTEXT.md §10): refresh token reuse detection (token family).
-- A rotated token is no longer deleted immediately but marked with the rotation
-- timestamp; presenting it again revokes every refresh token of the owning user.

ALTER TABLE refresh_tokens
    ADD COLUMN rotated_date TIMESTAMP(6) WITH TIME ZONE;

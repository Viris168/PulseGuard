-- Account state, so an account can actually be disabled or locked out.
ALTER TABLE users
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN locked  BOOLEAN NOT NULL DEFAULT FALSE;

-- Email verification and email change.

-- NULL until the owner clicks the link sent at sign-up. Every account that exists before this
-- migration counts as verified, so nothing changes for current users.
ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMPTZ;
UPDATE users SET email_verified_at = now();

-- Emailed links for VERIFY (the address being verified) and CHANGE (the address to switch
-- to). As with password reset, only a SHA-256 of each token is stored.
CREATE TABLE email_tokens (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose     VARCHAR(20)  NOT NULL,
    email       VARCHAR(255) NOT NULL,
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_email_tokens_user ON email_tokens (user_id);
CREATE INDEX idx_email_tokens_expiry ON email_tokens (expires_at);

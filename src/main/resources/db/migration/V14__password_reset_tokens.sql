-- "Forgot password" links. Only a SHA-256 of each token is stored, so a copy of this table
-- cannot be turned into working links. A token is deleted when used (single use) or when a
-- newer link is requested; expired ones are purged as new requests come in.
CREATE TABLE password_reset_tokens (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash  VARCHAR(64)  NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_password_reset_tokens_user ON password_reset_tokens (user_id);
CREATE INDEX idx_password_reset_tokens_expiry ON password_reset_tokens (expires_at);

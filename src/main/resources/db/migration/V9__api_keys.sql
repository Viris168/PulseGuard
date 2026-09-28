-- Personal API keys. Only a SHA-256 of the key is stored; the key itself is shown once, at
-- creation. Keys are long and random, so a fast hash is enough (no brute-forcing 190 bits),
-- and the unique index doubles as the lookup on every API request.
CREATE TABLE api_keys (
    id            BIGSERIAL    PRIMARY KEY,
    user_id       BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name          VARCHAR(50)  NOT NULL,
    prefix        VARCHAR(20)  NOT NULL,
    key_hash      VARCHAR(64)  NOT NULL UNIQUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at  TIMESTAMPTZ
);

CREATE INDEX idx_api_keys_user ON api_keys (user_id);
-- Names tell keys apart in the list, so one account cannot have two that differ only in case.
CREATE UNIQUE INDEX uq_api_keys_user_name ON api_keys (user_id, lower(name));

CREATE TABLE users (
    id                  BIGSERIAL PRIMARY KEY,
    email               VARCHAR(255) UNIQUE NOT NULL,
    password_hash       VARCHAR(255) NOT NULL,
    plan                VARCHAR(20)  NOT NULL DEFAULT 'FREE',
    stripe_customer_id  VARCHAR(100),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE monitors (
    id                    BIGSERIAL PRIMARY KEY,
    user_id               BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name                  VARCHAR(100) NOT NULL,
    url                   TEXT         NOT NULL,
    method                VARCHAR(10)  NOT NULL DEFAULT 'GET',
    expected_status       INT          NOT NULL DEFAULT 200,
    interval_seconds      INT          NOT NULL DEFAULT 300,
    timeout_ms            INT          NOT NULL DEFAULT 10000,
    state                 VARCHAR(20)  NOT NULL DEFAULT 'UP',
    consecutive_failures  INT          NOT NULL DEFAULT 0,
    consecutive_successes INT          NOT NULL DEFAULT 0,
    is_active             BOOLEAN      NOT NULL DEFAULT TRUE,
    last_checked_at       TIMESTAMPTZ,
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_monitors_user ON monitors (user_id);

CREATE TABLE checks (
    id                BIGSERIAL PRIMARY KEY,
    monitor_id        BIGINT      NOT NULL REFERENCES monitors(id) ON DELETE CASCADE,
    result            VARCHAR(10) NOT NULL,          -- UP / DOWN
    status_code       INT,
    response_time_ms  INT,
    error_type        VARCHAR(30),                   -- TIMEOUT, DNS, SSL, CONNECTION, STATUS_MISMATCH
    error_message     TEXT,
    checked_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_checks_monitor_time ON checks (monitor_id, checked_at DESC);

CREATE TABLE incidents (
    id           BIGSERIAL PRIMARY KEY,
    monitor_id   BIGINT      NOT NULL REFERENCES monitors(id) ON DELETE CASCADE,
    status       VARCHAR(20) NOT NULL DEFAULT 'OPEN',   -- OPEN / RESOLVED
    cause        TEXT,
    started_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at  TIMESTAMPTZ
);
-- At most one open incident per monitor
CREATE UNIQUE INDEX uq_one_open_incident ON incidents (monitor_id) WHERE status = 'OPEN';

CREATE TABLE notification_channels (
    id        BIGSERIAL PRIMARY KEY,
    user_id   BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type      VARCHAR(20) NOT NULL,       -- EMAIL, SLACK, TELEGRAM, SMS, WEBHOOK
    target    TEXT        NOT NULL,       -- email address, webhook URL, chat id...
    enabled   BOOLEAN     NOT NULL DEFAULT TRUE
);

CREATE TABLE notifications (
    id           BIGSERIAL PRIMARY KEY,
    incident_id  BIGINT      NOT NULL REFERENCES incidents(id) ON DELETE CASCADE,
    channel_id   BIGINT      NOT NULL REFERENCES notification_channels(id) ON DELETE CASCADE,
    event_type   VARCHAR(20) NOT NULL,    -- OPENED / RESOLVED
    status       VARCHAR(20) NOT NULL,    -- SENT / FAILED
    sent_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (incident_id, channel_id, event_type)
);

CREATE TABLE subscriptions (
    id                      BIGSERIAL PRIMARY KEY,
    user_id                 BIGINT UNIQUE NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    stripe_subscription_id  VARCHAR(100) UNIQUE,
    plan                    VARCHAR(20) NOT NULL,
    status                  VARCHAR(30) NOT NULL,   -- active, past_due, canceled...
    current_period_end      TIMESTAMPTZ
);

CREATE TABLE stripe_events (
    event_id      VARCHAR(100) PRIMARY KEY,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE check_daily_stats (
    monitor_id        BIGINT NOT NULL REFERENCES monitors(id) ON DELETE CASCADE,
    day               DATE   NOT NULL,
    total_checks      INT    NOT NULL,
    failed_checks     INT    NOT NULL,
    avg_response_ms   INT,
    p95_response_ms   INT,
    PRIMARY KEY (monitor_id, day)
);

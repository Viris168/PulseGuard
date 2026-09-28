-- Heartbeat monitors: instead of PulseGuard calling a URL, the user's job calls ours.
-- Silence past ping_deadline (last ping + period + grace) means down.
ALTER TABLE monitors
    ADD COLUMN type             VARCHAR(20) NOT NULL DEFAULT 'HTTP',
    ADD COLUMN grace_seconds    INT,
    -- The secret in the ping URL. Stored as-is, unlike API keys: the owner is shown the URL
    -- again at any time, and all it can do is report "still alive" for this one monitor.
    ADD COLUMN heartbeat_token  VARCHAR(64) UNIQUE,
    -- NULL until the first ping: monitoring starts when the job first checks in.
    ADD COLUMN ping_deadline    TIMESTAMPTZ,
    ADD CONSTRAINT chk_monitors_heartbeat_fields
        CHECK (type <> 'HEARTBEAT' OR (heartbeat_token IS NOT NULL AND grace_seconds IS NOT NULL));

-- The sweeper's query: active heartbeats whose deadline has passed.
CREATE INDEX idx_monitors_heartbeat_deadline ON monitors (ping_deadline)
    WHERE type = 'HEARTBEAT' AND is_active;

CREATE TABLE pings (
    id           BIGSERIAL    PRIMARY KEY,
    monitor_id   BIGINT       NOT NULL REFERENCES monitors(id) ON DELETE CASCADE,
    received_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    source_ip    VARCHAR(45)  NOT NULL  -- fits IPv6
);

CREATE INDEX idx_pings_monitor_time ON pings (monitor_id, received_at DESC);

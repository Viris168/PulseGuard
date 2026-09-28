-- PRD 2.1: several accepted status codes, optional request headers and body for HTTP checks.

-- A list replaces the single code; every existing monitor keeps the one it had.
ALTER TABLE monitors ADD COLUMN expected_statuses INT[] NOT NULL DEFAULT '{200}';
UPDATE monitors SET expected_statuses = ARRAY[expected_status];
ALTER TABLE monitors DROP COLUMN expected_status;

-- Sent with POST and PUT checks only.
ALTER TABLE monitors ADD COLUMN request_body TEXT;

-- Values can be credentials (Authorization, API keys) for the monitored service. They are
-- needed in full to send the request, so they are stored as-is; the API never returns the
-- value of a header whose name looks secret.
CREATE TABLE monitor_headers (
    monitor_id  BIGINT        NOT NULL REFERENCES monitors(id) ON DELETE CASCADE,
    position    INT           NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    value       VARCHAR(2000) NOT NULL,
    PRIMARY KEY (monitor_id, position)
);

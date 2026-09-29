-- Failed alerts are retried with backoff instead of being given up at the first error.
-- attempts counts sends so far; next_attempt_at is set while a retry is still due and
-- cleared once the alert is sent or given up.
ALTER TABLE notifications
    ADD COLUMN attempts         INT         NOT NULL DEFAULT 1,
    ADD COLUMN next_attempt_at  TIMESTAMPTZ;

CREATE INDEX idx_notifications_retry_due ON notifications (next_attempt_at)
    WHERE status = 'FAILED' AND next_attempt_at IS NOT NULL;

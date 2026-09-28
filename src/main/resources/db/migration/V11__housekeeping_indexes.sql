-- The nightly rollup reads one UTC day of checks across every monitor, and retention deletes
-- by age across every monitor. Without these, both scan the whole table.
CREATE INDEX idx_checks_checked_at ON checks (checked_at);
CREATE INDEX idx_pings_received_at ON pings (received_at);

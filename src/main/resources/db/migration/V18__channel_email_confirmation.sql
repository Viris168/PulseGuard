-- An email alert channel for any address other than the account's own verified one must be
-- confirmed from that inbox before alerts go there: otherwise anyone could point alerts at a
-- stranger. Channels that exist today are grandfathered in as confirmed.
ALTER TABLE notification_channels ADD COLUMN verified_at TIMESTAMPTZ;
UPDATE notification_channels SET verified_at = now() WHERE type = 'EMAIL';

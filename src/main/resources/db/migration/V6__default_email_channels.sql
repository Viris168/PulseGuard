-- Registration now creates a default EMAIL channel for the account email. Give every
-- existing user the same, unless they already have an email channel.
INSERT INTO notification_channels (user_id, type, target, enabled)
SELECT u.id, 'EMAIL', u.email, TRUE
FROM users u
WHERE NOT EXISTS (
    SELECT 1 FROM notification_channels c WHERE c.user_id = u.id AND c.type = 'EMAIL'
);

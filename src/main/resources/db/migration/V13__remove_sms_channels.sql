-- SMS is no longer on any plan: nothing could ever send it, so no SMS channel ever delivered
-- an alert. Remove them rather than leave dead rows in users' channel lists. (Alert history
-- rows can't reference them: channels without a sender were never claimed.)
DELETE FROM notification_channels WHERE type = 'SMS';

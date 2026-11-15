-- When the Stripe read behind the stored state began. Webhook deliveries fetch the subscription
-- before taking the user lock, so a slow older read can reach the lock after a newer one; it is
-- then ignored instead of overwriting (say) a cancellation with the old "active".
ALTER TABLE subscriptions
    ADD COLUMN snapshot_at TIMESTAMPTZ;

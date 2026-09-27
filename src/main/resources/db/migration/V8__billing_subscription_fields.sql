-- Set when the user cancels in the Stripe portal: the plan stays paid until the period ends,
-- then customer.subscription.deleted moves the account to FREE.
ALTER TABLE subscriptions
    ADD COLUMN cancel_at_period_end BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN updated_at           TIMESTAMPTZ NOT NULL DEFAULT now();

-- Webhooks find the account by Stripe customer id; one customer must never map to two users.
-- NULLs (users who never started a checkout) do not conflict.
CREATE UNIQUE INDEX uq_users_stripe_customer_id ON users (stripe_customer_id);

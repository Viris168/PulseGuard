-- Stripe customers whose PulseGuard account was deleted. Deleting an account cancels its
-- subscription, and Stripe then sends events for a customer no account links to any more.
-- Those are acknowledged, not failed: failing them makes Stripe retry for days and can get the
-- webhook endpoint disabled. Any other unknown customer still fails, so Stripe retries it.
CREATE TABLE deleted_stripe_customers (
    customer_id  VARCHAR(100) PRIMARY KEY,
    deleted_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

# Stripe billing: local setup

Checkout, the Customer Portal and the webhook run against a Stripe **sandbox** (test mode).
No real money moves. How it works: `architecture.md` §3.8 and §5.2.

## 1. Keys and prices in `.env`

| Variable | Where it comes from |
|---|---|
| `STRIPE_SECRET_KEY` | Dashboard → Developers → API keys → Secret key (`sk_test_…`) |
| `STRIPE_WEBHOOK_SECRET` | Printed by `stripe listen` (step 3), `whsec_…` |
| `STRIPE_PRICE_PRO` / `STRIPE_PRICE_BUSINESS` | The **price** ids (`price_…`), not the product ids (`prod_…`) |
| `PULSEGUARD_APP_BASE_URL` | The frontend, `http://localhost:5173`; Stripe redirects back here |

The app refuses to start if a price is missing, both plans share a price, or FREE has one.
Secrets stay in `.env` (git-ignored). The frontend needs none of them: it only follows the URLs
the backend returns. `frontend/.env.local` holds the publishable key (`pk_test_…`), which only
Stripe.js would use.

Creating the prices with the [Stripe CLI](https://docs.stripe.com/stripe-cli), logged in to the
same account as the secret key (`stripe login`):

```bash
stripe products create --name="Pro"
stripe prices create --product=prod_... --unit-amount=1200 --currency=usd -d "recurring[interval]=month"
```

Same again for Business at `3900`. Amounts are in cents and must match `frontend/src/lib/plans.ts`.
Prices must be **recurring**: Checkout runs in subscription mode.

## 2. Customer Portal

Dashboard → Settings → Billing → Customer portal:

- **Subscriptions → customers can switch plans:** add the Pro and Business prices.
- **Cancellations:** at the end of the billing period (the app keeps the plan until then).

Without this, "Switch plan" and "Keep plan" open a portal with nothing to change.

## 3. Forward webhooks

```bash
stripe listen --events checkout.session.completed,customer.subscription.created,customer.subscription.updated,customer.subscription.deleted,invoice.payment_failed --forward-to localhost:8080/api/stripe/webhook
```

Newer CLIs require `--events`; these five are the ones the app handles. Keep it running. Its `whsec_…` goes in `.env`; restart the backend after changing `.env`.
Without it, payments succeed on Stripe but the plan never changes here.

## 4. Try it

Billing page → Upgrade to Pro → pay with a test card. `stripe listen` should show
`checkout.session.completed` and `customer.subscription.created` answered `[200]`, and the page
switches from "Confirming your payment…" to "You're on Pro now".

| Card | Result |
|---|---|
| `4242 4242 4242 4242` | Succeeds |
| `4000 0025 0000 3155` | Asks for 3D Secure |
| `4000 0000 0000 0002` | Declined |
| `4000 0000 0000 0341` | Saves, then renewals fail: `past_due`, the "payment failed" banner |

Any future expiry and any CVC. Also useful: `postman/PulseGuard-Billing.postman_collection.json`.

## When the webhook answers non-2xx

Stripe retries every non-2xx for up to 3 days, so fixing the cause is usually enough.

| `stripe listen` shows | Cause |
|---|---|
| `400` | Signature: `STRIPE_WEBHOOK_SECRET` is not the one `stripe listen` printed, or the backend was not restarted |
| `500` "Event could not be processed" | Log says which: a price not in `.env`, or a Stripe customer no PulseGuard account is linked to (e.g. from `stripe trigger`, which makes throwaway customers) |
| `502` | The backend could not reach Stripe to fetch the subscription: wrong `STRIPE_SECRET_KEY`, or offline |

## Going live

New live-mode products and prices, the live `sk_live_…`, and a webhook endpoint added in the
Dashboard (Developers → Webhooks) pointing at `https://<your-domain>/api/stripe/webhook` for
`checkout.session.completed`, `customer.subscription.created|updated|deleted` and
`invoice.payment_failed`. Its signing secret replaces the `stripe listen` one.

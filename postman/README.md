# Postman

Two collections: **Auth** (below) and **Billing** (at the end).

`PulseGuard-Auth.postman_collection.json` — 25 requests, 49 assertions, covering every auth
endpoint plus the security behaviour that is worth seeing rather than taking on trust.

## Run it

```bash
docker compose up -d
./mvnw spring-boot:run
```

Import the collection in Postman, open the **Collection Runner**, run the folders top to bottom.
Tokens are captured between steps automatically — nothing needs copy-pasting.

Headless, same result:

```bash
npx newman run postman/PulseGuard-Auth.postman_collection.json
```

## Folders

| Folder | What it shows |
|---|---|
| 1 - Happy path | Register → me → refresh → change password → login → logout, in order |
| 2 - Rejections | Duplicate email, validation, bad/missing/tampered tokens |
| 3 - Rate limiting | Five 401s then a 429. Run last — it trips a 15-minute throttle |

## Between runs

Rate-limit counters live in Redis for 15 minutes, and there is a 20-attempts-per-IP budget that
several consecutive runs will exhaust:

```bash
docker compose exec redis redis-cli FLUSHALL
```

## What the interesting steps prove

- **5** — a refresh token is single-use; the one consumed in step 3 cannot be replayed.
- **7 / 8** — changing the password kills every earlier session but not the caller's own.
- **9** — the first session's refresh token dies without ever being sent to the server.
- **14** — logout is global, not per-token.
- **Login, unknown email** — byte-identical to a wrong password, so accounts cannot be enumerated.

---

# Billing collection

`PulseGuard-Billing.postman_collection.json` — 14 requests, 27 assertions: the billing API and
the webhook's front door.

Needs real Stripe **test-mode** values in `.env` (see BILLING.md). Folder 1 calls Stripe: each run
creates one test customer and two Checkout Sessions, and never charges anything.

```bash
npx newman run postman/PulseGuard-Billing.postman_collection.json
```

| Folder | What it shows |
|---|---|
| 1 - Checkout and portal | Summary on Free → portal refused before a customer exists → Checkout URL → second checkout reuses the customer → portal URL → still Free |
| 2 - Rejections | Buying Free, missing or unknown plan, no token, webhook without or with a forged signature. No Stripe calls |

What the interesting steps prove:

- **4 / 7** — starting a checkout never changes the plan. Only Stripe's signed webhook does.
- **5** — one Stripe customer per user, however many checkouts: check the dashboard.
- **Webhook, forged signature** — the endpoint needs no token, and still rejects anything Stripe didn't sign.

To finish a payment, open the Checkout URL from step 4 (the Postman console prints it) and pay with
`4242 4242 4242 4242`. With `stripe listen` running, step 7 then shows the new plan.
Not covered here: checkout while already subscribed (409) — that needs a paid subscription first.

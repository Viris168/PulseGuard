# Deploying PulseGuard

One Docker image serves both the React app and the API on one address. The frontend calls
`/api/...` on its own origin, so there is no CORS to configure and no separate web server.

## Build and run

Tests are not run inside `docker build`, because they start containers themselves
(Testcontainers). Run them first:

```bash
./mvnw test
docker build -t pulseguard .
```

To run the image the production way on your machine, against the Postgres, Redis and Mailpit
from `docker-compose.yml` (settings come from `.env`, and the compose file points them at the
other containers):

```bash
docker compose --profile app up --build
```

Open http://localhost:8080. Stop any `./mvnw spring-boot:run` first, because it uses the same port.

## What the image does

- Runs as a non-root user with `SPRING_PROFILES_ACTIVE=prod` (`application-prod.yaml`).
- Sizes the Java heap to 75% of the container's memory limit.
- Health: `/actuator/health/liveness` (the app is alive) and `/actuator/health/readiness` (it
  can take traffic). Both are public and show no details. Point the host's health check at
  `liveness`, so a database or Redis outage does not get the container restarted.
- Logs one JSON object per line (Elastic Common Schema) to stdout.
- Trusts `X-Forwarded-For` and `X-Forwarded-Proto` from the host's proxy (private addresses only),
  so login rate limits apply to each real client, and HSTS is sent over https.
- Caches files under `/assets/` for a year, because their names change when their content does.
  `index.html` is never cached, so a new deploy shows up on the next page load.

## Environment variables

Every variable below is required unless it has a default. If one is missing, startup stops with
a message naming it. `.env.example` has working local values for all of them.

### Secrets

| Variable | Notes |
|---|---|
| `PULSEGUARD_JWT_SECRET` | `openssl rand -base64 48`. Changing it signs everyone out unless the old value goes in `PULSEGUARD_JWT_PREVIOUS_SECRET` |
| `SPRING_DATASOURCE_PASSWORD` | |
| `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | SMTP login (for Resend: `resend` and an API key) |
| `STRIPE_SECRET_KEY` | `sk_live_...` or `sk_test_...` |
| `STRIPE_WEBHOOK_SECRET` | From the Stripe dashboard webhook endpoint for `https://<your-domain>/api/stripe/webhook` |
| `SPRING_DATA_REDIS_PASSWORD` | Optional. Managed Redis usually has one |

### Addresses

| Variable | Example / notes |
|---|---|
| `PULSEGUARD_APP_BASE_URL` | `https://pulseguard.example.com`, the public address. Alert links and Stripe redirects use it |
| `PULSEGUARD_PING_BASE_URL` | Usually the same address. Ping URLs are `{this}/api/ping/{token}`. Production has no default |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://host:5432/pulseguard?sslmode=require` |
| `SPRING_DATASOURCE_USERNAME` | |
| `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT` | |
| `SPRING_DATA_REDIS_SSL_ENABLED` | Default `false`. Set it to `true` for managed Redis that requires TLS |
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` | e.g. `smtp.resend.com`, `587` |
| `PULSEGUARD_NOTIFICATION_FROM` | `PulseGuard <alerts@your-verified-domain>` |
| `SERVER_PORT` | Default `8080`. Set it if the host assigns a port |

The production profile requires SMTP login and STARTTLS. Only local Mailpit turns them off, with
`PULSEGUARD_MAIL_SMTP_AUTH=false` and `PULSEGUARD_MAIL_STARTTLS_REQUIRED=false`.

### Tuning

Copy the remaining `PULSEGUARD_*` settings and `STRIPE_PRICE_PRO` / `STRIPE_PRICE_BUSINESS` from
`.env.example`. Its values are sensible production defaults.

Each instance opens up to 15 database connections. Keep instances × 15 under your database
plan's connection limit, or lower it with `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`.

# Deploying PulseGuard

One Docker image serves both the React app and the API on one address. The frontend calls
`/api/...` on its own origin, so there is no CORS to configure and no separate web server.

## Build and run locally

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

## Hosting on one server (Oracle Cloud Always Free)

`deploy/` runs everything on one Linux server: the app, Postgres, Redis, and Caddy, which serves
HTTPS with a free Let's Encrypt certificate. Only ports 80 and 443 are open to the internet.
Email goes out through Gmail. It works on any Ubuntu server with Docker; the Oracle-specific
parts are steps 1 and 2.

### 1. Create the server

1. Sign up at https://www.oracle.com/cloud/free/. It asks for a card to check your identity;
   Always Free resources are not charged. The home region can't be changed later, and free ARM
   servers are sometimes out of capacity in busy regions.
2. **Compute → Instances → Create instance**:
   - Image: **Canonical Ubuntu 24.04**.
   - Shape: **Ampere → VM.Standard.A1.Flex**, 2 OCPUs and 12 GB of memory (the free allowance
     is 4 OCPUs and 24 GB in total). If it says "out of capacity", try again later or pick
     another availability domain.
   - Networking: keep "Assign a public IPv4 address" on.
   - SSH keys: upload your public key (`~/.ssh/id_ed25519.pub`) or download the generated one.
3. Note the instance's **public IP address**, then connect: `ssh ubuntu@<public-ip>`.

Oracle may reclaim Always Free servers that stay almost idle for 7 days. Upgrading the account
to Pay As You Go stops that and still costs nothing while you stay within the free allowance.

### 2. Open ports 80 and 443

Oracle blocks them in two places, and both need opening.

- **Cloud firewall:** on the instance page, open the subnet → its **Default Security List** →
  **Add Ingress Rules**: source `0.0.0.0/0`, TCP, destination port `80,443`.
- **Server firewall:** Oracle's Ubuntu images reject new connections in iptables. On the server:

  ```bash
  sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 80 -j ACCEPT
  sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 443 -j ACCEPT
  sudo netfilter-persistent save
  ```

### 3. Give it a name

HTTPS certificates need a domain name. Without buying one, use DuckDNS: sign in at
https://www.duckdns.org, add a subdomain (e.g. `pulseguard` → `pulseguard.duckdns.org`), and set
its IP to the server's public IP. A domain you own works the same way: add an `A` record pointing
at the IP.

Check it from your own machine before going on: `ping pulseguard.duckdns.org` should show the
server's IP.

### 4. Install Docker and get the code

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
exit
```

Log back in (so the group change applies), then:

```bash
git clone https://github.com/Viris168/PulseGuard.git
cd PulseGuard/deploy
```

If the repository is private, GitHub asks for a username and a personal access token
(GitHub → Settings → Developer settings → Personal access tokens, with read access to the repo).

### 5. Create a Gmail app password

Gmail does not accept your normal password over SMTP.

1. Google Account → **Security** → turn on **2-Step Verification**.
2. Search the account settings for **App passwords**, create one called `PulseGuard`, and copy
   the 16-character password.

Gmail sends about 500 emails a day, which is plenty for alerts, and only as the account itself,
so `PULSEGUARD_NOTIFICATION_FROM` must be that Gmail address. Oracle blocks outgoing port 25,
but Gmail uses 587, which is open.

### 6. Fill in the settings

```bash
cp .env.example .env
chmod 600 .env
openssl rand -base64 48   # run twice: one for PULSEGUARD_JWT_SECRET, one for SPRING_DATASOURCE_PASSWORD
nano .env
```

Replace every `replace_me`. `SPRING_DATASOURCE_PASSWORD` is only read the first time Postgres
starts; changing it later means changing the password inside the database too.

### 7. Start it

```bash
docker compose up -d --build
docker compose logs -f app caddy
```

The first build takes around 10 minutes on the server. Once the app logs `Started`, and Caddy
logs `certificate obtained successfully`, open `https://<your-domain>` and create your account.
Press Ctrl+C to stop following the logs; the containers keep running and restart after a reboot.

If the certificate fails, the name doesn't point at the server yet, or port 80 is still closed
(step 2).

### 8. Nightly backups

`deploy/backup.sh` dumps the database to `~/pulseguard-backups` and keeps 14 days. Run it once
by hand, then schedule it with `crontab -e`:

```
0 3 * * * $HOME/PulseGuard/deploy/backup.sh >> $HOME/pulseguard-backups/backup.log 2>&1
```

The backups sit on the same disk as the database, so copy them off the server now and then:
`scp -r ubuntu@<public-ip>:pulseguard-backups .`

### Updating

```bash
cd ~/PulseGuard && git pull && cd deploy && docker compose up -d --build
```

Flyway applies new database migrations when the app starts.

### Stripe

Test mode is free. In the Stripe dashboard, add a webhook endpoint for
`https://<your-domain>/api/stripe/webhook`, and put its signing secret in `STRIPE_WEBHOOK_SECRET`.

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

For the one-server setup, `deploy/.env.example` lists everything, and `deploy/docker-compose.yml`
fills in the database, Redis and public addresses. The tables below are for hosting the image
anywhere else.

Every variable below is required unless it has a default. If one is missing, startup stops with
a message naming it. `.env.example` has working local values for all of them.

### Secrets

| Variable | Notes |
|---|---|
| `PULSEGUARD_JWT_SECRET` | `openssl rand -base64 48`. Changing it signs everyone out unless the old value goes in `PULSEGUARD_JWT_PREVIOUS_SECRET` |
| `SPRING_DATASOURCE_PASSWORD` | |
| `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD` | SMTP login. For Gmail: the Gmail address and an app password |
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
| `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` | e.g. `smtp.gmail.com`, `587` |
| `PULSEGUARD_NOTIFICATION_FROM` | `PulseGuard <you@gmail.com>`. Must be an address the SMTP account may send as |
| `SERVER_PORT` | Default `8080`. Set it if the host assigns a port |

The production profile requires SMTP login and STARTTLS. Only local Mailpit turns them off, with
`PULSEGUARD_MAIL_SMTP_AUTH=false` and `PULSEGUARD_MAIL_STARTTLS_REQUIRED=false`.

### AI (optional)

AI incident summaries and Ask AI are off unless `PULSEGUARD_AI_PROVIDER` is set. With it off,
nothing is sent to any AI provider and the dashboard shows its built-in incident summary.

| Variable | Notes |
|---|---|
| `PULSEGUARD_AI_PROVIDER` | `none` (default), `google-genai` or `anthropic` |
| `GOOGLE_AI_API_KEY` | For `google-genai`, from aistudio.google.com. Enable billing before real customers use it: the free tier may use prompts to improve Google's products, and prompts contain customers' monitor data |
| `ANTHROPIC_API_KEY` | For `anthropic`, from console.anthropic.com. Set a monthly spend limit in the console |
| `PULSEGUARD_AI_GOOGLE_MODEL` | Default `gemini-3.1-flash-lite`. Google retires model names; if every AI request fails, check the model still exists |
| `PULSEGUARD_AI_SUMMARY_MODEL` | Anthropic model, default `claude-haiku-4-5` |
| `PULSEGUARD_AI_TIMEOUT` | Default `20s`, the longest a request waits for the model |
| `PULSEGUARD_AI_FAIR_USE_DAILY_QUESTIONS` | Default `500`, the Ask AI cap per day on Business. Free (5) and Pro (100) are fixed in `PlanLimits` |

Failed AI calls are logged as `AI … failed` or `AI … timed out`, with the error class but never
the prompt. Each answered call logs its token counts.

### Tuning

Copy the remaining `PULSEGUARD_*` settings and `STRIPE_PRICE_PRO` / `STRIPE_PRICE_BUSINESS` from
`.env.example`. Its values are sensible production defaults.

Each instance opens up to 15 database connections. Keep instances × 15 under your database
plan's connection limit, or lower it with `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`.

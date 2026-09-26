# PulseGuard Roadmap & Ideas

Personal reminder of features discussed and the order to build them.

## Build order

1. **Incident engine** — `UP → SUSPICIOUS → DOWN → RECOVERING → UP`, hooked in at
   `MonitorCheckService` ("Step 4: hand the result to IncidentEngine here").
2. **Email alerts** — on incident open and resolve (this is what customers pay for).
3. **Connect frontend to real API** — `frontend/src/api/monitors.ts` is still mock data.
4. **Analytics dashboard** — see below.
5. **Stripe billing** — checkout, customer portal, webhook → set `user.plan`.
6. **Extras** — heartbeat URLs, public status page, Ask AI, API keys.

---

## 1. Analytics dashboard (inspired by Cloudflare Analytics)

Matches PRD 2.5. Data already exists: `checks` (result, statusCode, responseTimeMs,
checkedAt) and `check_daily_stats` (avg_response_ms, p95_response_ms).

### Cards (4) + charts (2)

| Card | Example |
|---|---|
| Uptime % | 99.3% ↘ 0.7% vs previous period |
| Avg response time | 142 ms ↗ 8% |
| P95 response time | 380 ms ↗ 21% |
| Incidents | 1 (30 min total) |

- **Response time chart** — line/area chart over the selected range.
- **Status bar** — green/red buckets showing when the monitor was down
  (reuse `frontend/src/components/ui/CheckBars.tsx`).
- **Range picker** — Last 24h / 7d / 30d.

Skip Cloudflare's "Cache hit rate" and "Build minutes"; they don't apply here.

### Backend

```
GET /api/monitors/{id}/stats?range=24h|7d|30d
```

Returns card values, `[{time, avgMs}]` chart points, and status buckets.

- **24h** → query `checks` directly, group into 15-min buckets,
  P95 via Postgres `percentile_cont(0.95)`.
- **7d / 30d** → read `check_daily_stats` (don't scan raw checks).
  Needs a **nightly rollup job** to fill that table (nothing writes it yet).
- Enforce history limit with `PlanLimits.retentionDays` (Free = 7 days).
- Always look up the monitor by `id` **and** `userId` (tenant isolation).
- Good candidate for Redis `@Cacheable`.

### Frontend

- Add **Recharts** (no chart library in `package.json` yet).
- Can start now with uptime + response time; incident count waits for the incident engine.

---

## 2. What a Pro user gets

No key or link. The **same account** gets higher limits (`PlanLimits`):

| | Free | Pro | Business |
|---|---|---|---|
| Monitors | 3 | 25 | Unlimited |
| Min interval | 5 min | 1 min | 1 min |
| Alerts | Email | Email + Slack | Email + Slack + SMS |
| History | 7 days | 90 days | 1 year |

Stripe flow: Upgrade → `POST /api/billing/checkout` → Stripe Checkout page →
webhook `checkout.session.completed` → verify signature, dedupe `stripe_event_id` →
set `user.plan = PRO`. Manage/cancel via Stripe Customer Portal.

---

## 3. Optional "key/link" features (possible Pro perks)

- **Heartbeat URLs** (best fit) — give the user `https://pulseguard.com/ping/<secret>`;
  their cron job calls it; alert if it stops arriving on time. (Like Healthchecks.io.)
- **Personal API keys** — `pg_live_...` for scripts/CI/Terraform. Store only a hash,
  show once (like GitHub tokens).
- **Public status page** — `/status/{slug}` users share with their own customers.

---

## 4. Ask AI (inspired by Cloudflare "Ask AI")

Answers questions about the user's own data: "Why did Shop API go down last night?",
"Which monitor is slowest this week?", "What does 502 mean?"

```
React panel → POST /api/ai/ask (JWT) → AiAssistantService
   1. load this user's monitors + recent failed checks (scoped by userId)
   2. send question + data to the LLM
   ← answer
```

Rules:
- API key lives **only on the backend**, never in React.
- Data scoped by `principal.getUserId()`, never chosen by the model or request.
- Rate limit per plan (e.g. Free 5/day) → natural Pro feature.

Provider plan:
- **Dev:** Ollama (free, local, private, no API key).
- **Abstraction:** Spring AI `ChatClient` so switching provider is config only.
- **Prod:** paid API (reliable, customer data not used for training). A small model
  such as Claude Haiku 4.5 costs well under $0.01 per question.
- Free-tier alternatives (Gemini, Groq, OpenRouter, Cloudflare Workers AI) — check current
  limits/terms; some free tiers may use prompts for training.

Cheaper first AI feature: **auto-summary per incident** in the alert email
(one call per incident, no chat UI needed).

---

## 5. Customer story (for landing page / demo)

Dara runs a shop API at `api.darashop.com/health`.
1. Signs up, adds 3 monitors (Free limit), checks every 5 min.
2. 02:13 API returns 500 → SUSPICIOUS → 02:23 third failure → DOWN → email alert.
3. She fixes it; 02:38 passes → RECOVERING → 02:43 → UP → "back up" email. 30 min
   downtime instead of 5 hours.
4. Next morning: dashboard shows 99.3% uptime and response time rising an hour before failure.
5. Needs 8 monitors at 1-min interval → upgrades to Pro via Stripe.

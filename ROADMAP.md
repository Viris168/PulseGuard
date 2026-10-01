# PulseGuard Roadmap & Ideas

Personal reminder of features discussed and the order to build them.

## Build order

1. **Incident engine** — `UP → SUSPICIOUS → DOWN → RECOVERING → UP`, hooked in at
   `MonitorCheckService` ("Step 4: hand the result to IncidentEngine here").
2. **Email alerts** — on incident open and resolve (this is what customers pay for).
3. **Connect frontend to real API** — `frontend/src/api/monitors.ts` is still mock data.
4. **Analytics dashboard** — see below.
5. **Stripe billing** — ✅ done: checkout, customer portal, webhook → set `user.plan`.
   Local setup in BILLING.md; downgrades slow monitors and switch off channels the plan drops.
6. **Slack alerts** — ✅ done: Pro and Business alerts reach a Slack incoming webhook
   (Settings → Alert channels). Telegram, SMS and generic webhooks follow the same pattern.
7. **Rollup and retention** — ✅ done: a nightly job summarises checks per day, then trims
   history to each plan's limit (Free 7 days, Pro 90, Business 365; raw checks 62 days at most).
8. **Extras** — heartbeat URLs, public status page, API keys; Ask AI ✅ done (see §4).

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
| Alerts | Email | Email + Slack | Email + Slack (SMS once a sender exists) |
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

## 4. Ask AI (inspired by Cloudflare "Ask AI") ✅ done

Answers questions about the user's own data: "Why did Shop API go down last night?",
"Which monitor is slowest this week?", "What does 502 mean?" Built in milestones (details and
learning notes in `AI_PLAN.md` and `ai-milestones/`):

- **Incident summary** on each incident page, with a rule-based fallback.
- **Ask AI chat:** saved conversations, streamed answers with Stop, follow-ups, 👍/👎.
- **Lookups (tools):** uptime, response times, incidents, incident details and failed checks for
  any range the plan keeps; each lookup shows as "Checked …" under the answer.
- **"Ask AI" buttons** on monitor and incident pages.
- **Help docs:** 16 articles on a public `/docs` page; Ask AI answers "how do I…" questions from
  them, with numbered sources linking to the right section, and says when the docs don't cover
  something.

Rules that held from the first sketch:
- API key lives **only on the backend**, never in React.
- Data scoped by the logged-in user, never chosen by the model or the request; only monitors
  the user shared with Ask AI.
- Questions per day by plan: Free 5, Pro 100, Business unlimited (fair use 500).

Provider: Spring AI `ChatModel`, switched by `PULSEGUARD_AI_PROVIDER` (Google Gemini
`gemini-3.1-flash-lite` in use; Anthropic Claude Haiku also wired). One lookup question costs
2 model requests.

Still open:
- Check streaming behind the production proxy (Caddy) after the next deploy.
- Usage and cost dashboard for the admin; per-plan rollout switch.
- Maybe later (`AI_PLAN.md` Milestone 4+): answers from customers' own documents (runbooks), and
  a support widget on public pages.

---

## 5. Customer story (for landing page / demo)

Dara runs a shop API at `api.darashop.com/health`.
1. Signs up, adds 3 monitors (Free limit), checks every 5 min.
2. 02:13 API returns 500 → SUSPICIOUS → 02:23 third failure → DOWN → email alert.
3. She fixes it; 02:38 passes → RECOVERING → 02:43 → UP → "back up" email. 30 min
   downtime instead of 5 hours.
4. Next morning: dashboard shows 99.3% uptime and response time rising an hour before failure.
5. Needs 8 monitors at 1-min interval → upgrades to Pro via Stripe.

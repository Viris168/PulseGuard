# Product Requirements Document (PRD)

## Project Name
**PulseGuard** *(working title — rename as desired)*
API Monitoring & Incident Platform

---

## 1. Overview

### 1.1 Summary
PulseGuard is a SaaS web service that continuously monitors user-registered APIs and websites, detects downtime or degraded performance, automatically opens and tracks incidents, and alerts users in real time. It gives individuals and teams visibility into the health of their systems without manual checking, closing the gap between "something broke" and "someone knows."

### 1.2 Problem Statement
Teams and solo developers running APIs, websites, or backend services often discover outages only after a customer complains, a transaction fails, or revenue is lost. Without active monitoring, detection time is measured in hours; with it, detection time drops to minutes — directly reducing downtime cost and reputational damage.

### 1.3 Target Users
- Solo developers / indie hackers running side projects or small SaaS products
- Small startup engineering teams without a dedicated ops/SRE function
- Freelancers/agencies managing client websites who need uptime proof for SLAs
- Internal teams monitoring internally-consumed APIs

### 1.4 Goals
- Let a user add a monitor (URL) in under 60 seconds
- Detect downtime within one polling interval and alert within seconds of detection
- Avoid false positives from single transient failures
- Provide a clear uptime/incident history per monitor
- Support a free tier and paid subscription tiers

### 1.5 Non-Goals (v1)
- Full APM / distributed tracing (this is uptime/health monitoring, not deep performance profiling)
- Multi-region polling (v1 polls from a single region/server)
- Native mobile app (web dashboard only)
- On-call scheduling / escalation policies (may be a future phase)

---

## 2. Core Features (MVP Scope)

### 2.1 Monitor Management
- Create, edit, delete, pause/resume a monitor
- Fields: name, URL, HTTP method, expected status code(s), timeout, polling interval, request headers (optional), request body (optional, for POST checks)
- Support HTTP/HTTPS endpoint checks (v1); ping/TCP checks out of scope for v1

### 2.2 Polling Engine
- Scheduled, recurring checks per monitor at its configured interval
- Records: status (up/down), HTTP status code, response time (ms), error message (if any), timestamp
- Configurable timeout per monitor
- Checks run asynchronously/concurrently without blocking other monitors

### 2.3 Incident Detection & Lifecycle
- State machine per monitor: `UP → SUSPICIOUS → DOWN → UP`
- Incident opens only after N consecutive failed checks (configurable threshold, default 3) to avoid false alarms from transient blips
- Incident auto-resolves when M consecutive successful checks occur
- Each incident records: start time, end time, duration, cause/reason, affected monitor

### 2.4 Alerting
- Email notification on incident open and incident resolve (v1)
- Alert includes: monitor name, URL, failure reason, time detected
- Stretch (v1.1+): Slack webhook, SMS, generic webhook

### 2.5 Dashboard
- List of monitors with live status badge (up/down/paused), current uptime %, last check time
- Per-monitor detail view: response time chart (last 24h/7d/30d), incident history, uptime % over time
- Incident list/detail view across all monitors

### 2.6 Public Status Page (Stretch)
- Optional, shareable, read-only page showing current + historical status of selected monitors
- Useful for teams who want to publish uptime to customers

### 2.7 Authentication & Accounts
- Email/password signup and login (Spring Security)
- Each user has their own isolated set of monitors/incidents

### 2.8 Subscription & Billing
- Stripe integration for subscription management
- Tiers gate: number of monitors, minimum polling interval, alert channels, history retention
- Stripe-hosted Customer Portal for upgrade/downgrade/cancel
- Webhook listener to sync subscription state into the app's database

**Proposed Tiers:**

| Tier | Price | Monitors | Min Interval | Alerts | History |
|---|---|---|---|---|---|
| Free | $0 | 3 | 5 min | Email | 7 days |
| Pro | $9–15/mo | 25 | 1 min | Email + Slack | 90 days |
| Business | $30–50/mo | Unlimited | 1 min | Email + Slack + SMS | 1 year |

---

## 3. User Stories

- As a user, I want to add a monitor by pasting a URL so I can start tracking uptime immediately.
- As a user, I want to be emailed the moment my API goes down so I can respond before customers notice.
- As a user, I want to see uptime % and incident history per monitor so I can report on reliability.
- As a user, I want a single failed check to *not* trigger a false alarm, so I'm not spammed by transient network blips.
- As a user, I want to upgrade to a paid plan so I can monitor more endpoints more frequently.
- As a free-tier user, I want to understand what I'd gain by upgrading, so I can decide whether to subscribe.

---

## 4. Technical Design

### 4.1 Stack
- **Backend:** Spring Boot (Spring Web, Spring Data JPA, Spring Security, Spring Scheduler/Quartz)
- **Database:** PostgreSQL
- **Queue/Cache (optional but recommended):** Redis
- **Async execution:** Spring `@Async` + `WebClient` (non-blocking HTTP checks) or thread pool executor
- **Email:** Spring Mail via SMTP provider (Resend/SendGrid)
- **Payments:** Stripe (Java SDK, Checkout, Customer Portal, Webhooks)
- **Frontend:** Thymeleaf (server-rendered) or separate React/Vue SPA calling the REST API

### 4.2 Data Model (high-level)

**users**
- id, email, password_hash, created_at, subscription_tier, stripe_customer_id

**monitors**
- id, user_id, name, url, method, expected_status, interval_seconds, timeout_ms, is_active, created_at

**checks**
- id, monitor_id, status, status_code, response_time_ms, error, checked_at

**incidents**
- id, monitor_id, started_at, resolved_at, cause, status

**subscriptions**
- id, user_id, stripe_subscription_id, plan, status, current_period_end

### 4.3 Scheduling Approach
- Quartz (DB-backed job store) preferred for dynamic, per-monitor intervals that persist across restarts
- Alternative: simple `@Scheduled` polling loop that queries due monitors from Postgres every N seconds (simpler, less precise, fine for MVP)

### 4.4 Key Non-Functional Requirements
- Polling must not block on slow/unresponsive endpoints (enforce timeouts strictly)
- System must tolerate worker restarts without losing scheduled monitors
- Incident detection must be idempotent (no duplicate incidents from concurrent check races)
- Dashboard data queries should stay performant as check history grows (index `monitor_id, checked_at`; consider periodic archiving/rollup of old check data)

---

## 5. Success Metrics

- Time from signup to first monitor created (target: < 2 minutes)
- Mean time to alert after failure detected (target: < 30 seconds after threshold met)
- False-positive incident rate (target: < 5% of opened incidents auto-resolve within one check cycle)
- Free-to-paid conversion rate (track once billing is live)
- Monitor check success rate / system uptime of PulseGuard itself

---

## 6. Milestones / Rollout Plan

| Phase | Deliverable |
|---|---|
| M1 | Auth + monitor CRUD API |
| M2 | Polling engine (scheduling + async checks) |
| M3 | Incident detection state machine |
| M4 | Email alerting |
| M5 | Dashboard UI |
| M6 | Stripe subscription integration + plan gating |
| M7 (stretch) | Public status page, Slack alerts |

---

## 7. Open Questions

- Single-region polling only for v1 — acceptable, or is multi-region a must-have from day one?
- Should free tier require a credit card at signup, or fully card-less?
- Retention policy for raw check data at scale (archive/rollup strategy) — decide before history grows unbounded.
- Any compliance requirements (GDPR data handling) if monitoring targets could include user-supplied URLs pointing to sensitive internal systems?

---

*Document owner: [Your Name]*
*Last updated: [Date]*
*Status: Draft — v1*

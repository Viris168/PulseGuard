---
title: Plans and limits
summary: What each plan includes, what happens at a limit, and what changes when you upgrade or downgrade.
describes: billing/PlanLimits.java, billing/PlanChangeService.java, monitor/MonitorService.java, frontend/src/pages/BillingPage.tsx, stats/RetentionService.java, ai/AiQuotaPolicy.java, application.yaml (pulseguard.housekeeping, pulseguard.ai.fair-use-daily-questions)
---

PulseGuard has three plans: **Free**, **Pro** and **Business**. Every plan checks your monitors
the same way and opens incidents by the same rules; the plans differ in how much you can monitor,
how often, how long history is kept, where alerts can go, and how many Ask AI questions you can
ask. Current prices are on the **Billing** page.

## What each plan includes

| | Free | Pro | Business |
|---|---|---|---|
| Monitors | 3 | 25 | Unlimited |
| Fastest check interval | 5 minutes | 1 minute | 1 minute |
| History | 7 days | 90 days | 365 days |
| Alert channels | Email | Email, Slack | Email, Slack |
| Ask AI questions per day | 5 | 100 | Unlimited (fair use: 500) |

Status pages, heartbeat monitors and API keys are available on every plan.

## Monitors and check intervals

The monitor limit counts every monitor you have, paused ones included. At the limit, you can't
add another until you delete one or upgrade.

The fastest check interval applies to HTTP monitors: on Free the shortest choice is every 5
minutes. It doesn't apply to heartbeat monitors, whose period is simply how often your own job
runs.

## History

History is how far back you can look at a monitor's uptime, response times and checks:

- The individual checks are kept for your plan's history, but never more than **62 days**.
- After that, each day's figures (checks, failures, average and 95th-percentile response times) are kept
  for the rest of your plan's history: up to 90 days on Pro and 365 days on Business.
- Older data is deleted each night.

Ask AI chats you haven't used for longer than your plan's history are deleted too.

## Ask AI questions

Each question you ask Ask AI counts once, whatever it looks up to answer. The count **resets at
midnight UTC**. A question that fails before any answer appears isn't counted.

Business is unlimited for normal use; a fair-use cap of 500 questions a day protects the service
from runaway scripts.

## Upgrading

Choose a plan on the **Billing** page and pay through Stripe's secure checkout. The new limits
apply as soon as the payment goes through: you can add monitors, pick faster intervals and add
a Slack channel straight away.

Manage your card, invoices and cancellation from **Billing → Manage billing**.

## Downgrading

Change or cancel your plan from **Billing → Manage billing**. If you cancel, you keep your paid
plan until the end of the period you've paid for, then move to Free; the Billing page shows the
date.

When you move to a smaller plan, PulseGuard brings your account within its limits for you:

- **Faster monitors are slowed down** to the new plan's fastest interval. Heartbeat monitors are
  left alone.
- **Alert channels the plan doesn't include are switched off**, not deleted. After upgrading
  again, switch them back on under **Settings → Alert channels**. Your email channel keeps
  working.
- **Monitors over the new limit keep running.** You just can't add more until you're under the
  limit. The Billing page shows how many you have against the new plan's limit.
- **History beyond the new plan's limit is deleted** at the next nightly clean-up.

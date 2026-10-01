---
title: History and retention
summary: How long PulseGuard keeps your checks, pings, daily figures, incidents and Ask AI chats, and when old data is deleted.
describes: stats/RetentionService.java, billing/PlanLimits.java, application.yaml (pulseguard.housekeeping), statuspage/PublicStatusPageService.java, frontend/src/pages/MonitorDetailPage.tsx
---

PulseGuard keeps your monitoring history for as long as your plan allows: **7 days on Free, 90
days on Pro and 365 days on Business**. This article explains what exactly is kept, in how much
detail, and when older data goes.

## Two levels of detail

- **Individual checks** (every result, its status code, response time and error) and heartbeat
  **pings** are kept for your plan's history, but **never more than 62 days**.
- **Daily figures**, one row per monitor per day (number of checks, how many failed, average and
  95th-percentile response time), are kept for your plan's full history.

So on Business, the last 62 days have every check, and the rest of the year has daily figures.
Uptime and response times for older periods come from those daily figures; days count in UTC.

## What the charts show

On a monitor's page you can view the last **24 hours**, **7 days** or **30 days**. Ranges longer
than your plan keeps are locked: on Free, the 30-day button is greyed out with the note "Free
keeps 7 days of history".

A public status page shows up to 90 days of daily bars, or fewer if your plan keeps less.

## Incidents

Incidents are kept for as long as the monitor exists, whatever your plan. Their timelines refer
to the individual checks, so very old incidents show fewer details once those checks are gone.
Deleting a monitor deletes its incidents too.

## Ask AI chats

An Ask AI chat you haven't used for longer than your plan's history (7, 90 or 365 days) is
deleted. Ask AI can also only look up data your plan still keeps: on Free, asking about last
month gets "that's older than your plan keeps".

## When old data is deleted

A clean-up runs every night at about **03:15 UTC**. It first saves yesterday's daily figures, then
deletes whatever is older than your plan keeps.

After a **downgrade**, the next clean-up applies the smaller plan's limits, so history beyond them
is deleted that night. Upgrading doesn't bring deleted data back, but from then on more is kept.

## Deleting everything

Deleting your account deletes all of it at once: monitors, checks, pings, incidents, alert
channels, status page and chats.

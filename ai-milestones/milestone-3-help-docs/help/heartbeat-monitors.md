---
title: Heartbeat monitors
summary: Watch cron jobs, backups and workers: your job pings a PulseGuard URL, and you're alerted when the pings stop.
describes: heartbeat/PingController.java, heartbeat/HeartbeatService.java, heartbeat/HeartbeatSchedule.java, monitor/dto/MonitorRequestValidator.java, frontend/src/components/monitors/HeartbeatSetup.tsx, frontend/src/pages/MonitorFormPage.tsx
---

A heartbeat monitor turns monitoring around: instead of PulseGuard calling you, **your job calls
PulseGuard** each time it finishes. If the calls stop, something went wrong: the job crashed,
the server is off, or the cron entry was deleted. Use it for backups, scheduled reports, queue
workers, or anything that can't be reached from the internet.

## Setting one up

Add a monitor and choose **Heartbeat**. Give it a name and two settings:

- **Period**: how often your job runs, from every 5 minutes to every week (every hour by
  default). The plan's fastest check interval doesn't apply to heartbeats.
- **Grace period**: how late a ping may be before it counts as missed, from 1 minute to 6 hours
  (5 minutes by default). Give jobs that take a variable time some room.

After saving, the monitor page shows its **ping URL**, which looks like
`https://…/api/ping/AbC123…`. The last part is a secret token: anyone with the URL can send
pings for this monitor, so keep it out of public code.

## Sending pings

Call the ping URL at the end of your job, only when it succeeded. `GET`, `POST` and `HEAD` all
work, and the answer is simply `OK`. The monitor page has ready-made snippets, for example in a
crontab:

```
0 * * * *  /path/to/your-job.sh && curl -fsS -m 10 --retry 3 https://…/api/ping/AbC123… > /dev/null
```

The `&&` means the ping is only sent if the job succeeded, so a failing job looks like a missing
ping. `--retry 3` rides out a brief network problem.

Use **Send test ping** on the monitor page to check everything is wired up before the job first
runs.

## When it goes down

The deadline for the next ping is the last ping's time plus the period plus the grace period. If
nothing arrives by then, the monitor is **Down** straight away, an incident opens and you're
alerted. The next ping resolves it immediately.

A job that stays silent for days counts as one missed deadline and one incident, not one per
missed run.

## Pausing a heartbeat

While a heartbeat monitor is paused, pings are answered with
`OK (monitor paused, ping ignored)` and nothing is recorded, so your job doesn't fail just
because monitoring is paused. When you resume it, the job gets a full period plus grace from
that moment before a ping is due.

## Pings close together

Pings less than 10 seconds apart while the monitor is up are answered but not stored, so a job
stuck in a loop can't flood your history. They never cause a problem for a real schedule.

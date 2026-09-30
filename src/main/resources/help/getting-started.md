---
title: Getting started
summary: Create an account, add your first monitor and get alerted when it goes down.
describes: auth/AuthService.java, common/net/SafeUrlValidator.java, notification/NotificationChannel.java, scheduling/SchedulerService.java, frontend/src/pages/MonitorFormPage.tsx, frontend/src/pages/MonitorsPage.tsx, frontend/src/components/ui/StatusBadge.tsx
---

PulseGuard checks your APIs and websites on a schedule and tells you when they stop working.
This guide takes you from a new account to your first alert in a few minutes.

## Create your account

Sign up with your name, email address and a password. You start on the Free plan: no credit
card needed, and you can upgrade later from the Billing page.

After signing up, open the link in the verification email we send you. **Verify your email
address before you rely on alerts:** PulseGuard only sends alerts to an email address that has
been confirmed.

## Add your first monitor

Go to **Monitors** and choose **Add monitor**. There are two kinds:

- **HTTP monitor**: PulseGuard calls a URL on a schedule and checks the answer. Use it for APIs,
  health endpoints and websites.
- **Heartbeat monitor**: your own job (a backup, a cron job, a queue worker) calls a PulseGuard
  URL each time it runs. Use it for things that don't have a URL of their own.

For an HTTP monitor, give it a name and the URL to check, for example
`https://api.example.com/health`. The defaults are a good start: a `GET` request every 5 minutes,
a 10-second timeout, and status `200` counted as up. How often you can check depends on your plan
(every 5 minutes on Free, every minute on Pro and Business).

Save it, and the first check runs within about half a minute. It shows **Waiting** until then.

## Addresses you can't monitor

PulseGuard checks your monitors from the internet, so the URL must be reachable from the
internet:

- Only `http://` and `https://` URLs can be monitored.
- The host name must resolve. A typo in the domain is caught when you save.
- **Private and internal addresses are refused**, for security: `localhost`, `127.0.0.1`,
  `10.x.x.x`, `192.168.x.x`, `172.16–31.x.x` and similar. A URL on your laptop or office network
  can't be checked from outside it.

To watch something that isn't reachable from the internet, such as an internal job, use a
**heartbeat monitor** and have the job ping PulseGuard instead.

## Read the monitor list

Each monitor shows its status, its uptime over the last 24 hours, its latest response time and
when it was last checked. The status can be:

- **Waiting**: added, but not checked yet.
- **Up**: the latest checks passed.
- **Failing**: the latest check failed, but not enough in a row to call it an outage yet.
- **Down**: it failed several checks in a row, so an incident is open and you've been alerted.
- **Recovering**: it's passing again, and PulseGuard is making sure before closing the incident.
- **Paused**: you paused it; it isn't checked until you resume it.

Open a monitor to see its response-time chart, uptime and incidents over 24 hours, 7 days or 30
days.

## Get alerted

Every account gets an email alert channel for its own address, so once your email is verified
you'll hear about outages without setting anything up. You get one email when an incident opens
and one when it's resolved, not one per failed check.

Pro and Business plans can also send alerts to Slack. Manage alert channels under **Settings →
Alert channels**.

## What to do next

- Add the other endpoints you care about: the free plan includes 3 monitors.
- Read **How incidents work** to understand when PulseGuard decides something is down.
- Share your uptime with customers on a **status page**.
- Ask questions about your monitors in plain words with **Ask AI**, for example "Why did my API
  go down last night?"

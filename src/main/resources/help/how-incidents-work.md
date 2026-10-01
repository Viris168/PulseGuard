---
title: How incidents work
summary: When PulseGuard decides a monitor is down, opens an incident and alerts you, and when it closes it again.
describes: incident/MonitorStateMachine.java, monitor/MonitorService.java, check/CheckExecutor.java, stats/RetentionService.java, incident/IncidentEngine.java, heartbeat/HeartbeatSchedule.java, enumeration/ErrorType.java, application.yaml (pulseguard.incident)
---

An **incident** is a confirmed outage: a period when a monitor was down. PulseGuard is careful
about opening one, because every incident sends you an alert. A single failed check never opens
an incident; several failures in a row do.

## What counts as a failed check

An HTTP check fails when:

- **Timeout**: no answer within the monitor's timeout (10 seconds by default).
- **DNS**: the host name couldn't be found.
- **SSL**: the certificate couldn't be verified, for example it has expired.
- **Connection**: the server refused the connection or it dropped.
- **Status mismatch**: the server answered, but with a status code you didn't list as expected.
  By default only `200` counts as up, so a `404` or `503` is a failure. You can add other codes
  (for example `204`) to a monitor's expected statuses.
- **Redirects** are not followed, for security: a `301` or `302` is checked as it is. Point the
  monitor at the final URL, or add the redirect code to its expected statuses.

A check that answers slowly but within the timeout still passes. The failed check's error, such
as "Expected 200 but got 503", appears on the incident page.

## From up to down: three failures in a row

- **Up**: all is well.
- **Failing**: the latest check failed. Nothing is sent yet; it could be a one-off network blip.
  If the next check passes, the monitor goes back to **Up** and the failure is forgotten.
- **Down**: **3 checks in a row failed**. PulseGuard opens an incident and sends you an alert.

How long that takes depends on the monitor's interval: with checks every minute, you hear about
an outage about 3 minutes after it starts; with checks every 5 minutes, about 15 minutes.

## From down to up: two passes in a row

- **Recovering**: a check passed while the monitor was down. PulseGuard waits for one more to be
  sure.
- **Up**: **2 checks in a row passed**. The incident is resolved and you get a "resolved" alert.

If a check fails while recovering, the monitor goes back to **Down** and it's still the same
incident: you don't get a second "down" alert for a flicker.

## Heartbeat monitors

Heartbeats work the other way around: your job pings PulseGuard, and PulseGuard notices when the
pings stop. Each heartbeat has a **period** (how often your job runs) and a **grace period**
(how late it may be).

- If no ping arrives within the period plus the grace period, the monitor is **Down** straight
  away and an incident opens. The grace period already gave it the benefit of the doubt.
- The next ping resolves the incident at once: a ping is your job itself saying it ran.

A job that stays silent for days counts as one missed deadline and one incident, not one per
missed run.

## What you get told

You get **one alert when an incident opens and one when it's resolved**, on every alert channel
that's switched on: email, and Slack on Pro and Business plans. You don't get an alert for each
failed check.

## Pausing or deleting a monitor

- **Pausing** stops the checks but keeps everything as it is, including an open incident. It
  resolves once checks pass again after you resume. A paused heartbeat gets a full period from
  the moment you resume, so the pause itself isn't counted as a missed ping.
- **Deleting** a monitor deletes its checks and incidents with it. Pause it instead if you want
  to keep its history.

## The incident page

Every incident has a page (under **Incidents**) with:

- its cause, such as "STATUS_MISMATCH: Expected 200 but got 503";
- when it started, when it was resolved and how long it lasted;
- a **timeline**: the failed checks, when it was confirmed, which alerts were sent, and the
  passing checks that resolved it;
- a short **summary** in plain words.

A monitor has at most one open incident at a time. Incidents are kept for as long as the monitor
exists. The individual checks behind them are kept for 7 days on Free and up to 62 days on Pro
and Business; after that, daily uptime and response-time figures remain for your plan's full
history.

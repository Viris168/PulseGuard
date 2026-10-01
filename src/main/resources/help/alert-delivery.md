---
title: Alert delivery
summary: When alerts are sent, how failed alerts are retried, and how to see what was delivered.
describes: notification/NotificationService.java, notification/AlertRetryProperties.java, notification/channels/SlackDeliveryException.java, frontend/src/components/incidents/IncidentTimeline.tsx, frontend/src/pages/IncidentDetailPage.tsx, application.yaml (pulseguard.notification.retry)
---

Every incident sends two alerts to each of your enabled alert channels: a **down** alert when it
opens and a **recovery** alert when it's resolved. This article explains what happens behind that,
including when a message can't be delivered.

## Who gets an alert

An alert goes to every alert channel on your account that is:

- **enabled** (its switch is on);
- **confirmed**: your own email address verified, other addresses confirmed from their link;
- **included in your plan**: Slack on Pro and Business.

Each channel gets each alert once. Even if something retries behind the scenes, you never get the
same "down" alert twice for one incident on one channel.

## When alerts are sent

The down alert goes out as soon as the incident opens: after 3 failed checks in a row for an HTTP
monitor, or when a heartbeat's deadline passes. The recovery alert goes out when the incident is
resolved. Alerts are sent only once the incident is safely saved, so you're never alerted about
an incident that doesn't exist.

## When delivery fails

If an email server or Slack is having problems, PulseGuard doesn't give up. A failed alert is
retried **3 more times: after 1 minute, 5 minutes and 30 minutes**, so over about 36 minutes.

Some failures can't be fixed by waiting, so they aren't retried:

- Slack says the webhook doesn't exist or isn't allowed (for example, the app was removed).
- The email itself couldn't be built, for example because of an invalid address.

## Seeing what was delivered

Open the incident page:

- The **Alerts delivered** card shows how many alerts reached their channel, for example
  "2 of 2", or how many failed.
- The **timeline** has a line for each alert: "Down alert sent · Email", "Recovery alert failed ·
  Slack", with the address or channel it went to.

An alert shown as failed may still be retried within the half hour described above.

## Checking a channel before you need it

Use **Send test** on a channel under **Settings → Alert channels**. It sends a sample alert
straight away and tells you if it failed, so you find a broken webhook or a mistyped address on a
quiet day, not during an outage.

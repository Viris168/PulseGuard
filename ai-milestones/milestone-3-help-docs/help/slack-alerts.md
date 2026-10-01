---
title: Slack alerts
summary: Send down and recovered alerts to a Slack channel with an incoming webhook (Pro and Business plans).
describes: notification/ChannelService.java, notification/channels/SlackSender.java, notification/channels/SlackDeliveryException.java, billing/PlanLimits.java, frontend/src/pages/settings/ChannelsSection.tsx
---

On the **Pro** and **Business** plans, PulseGuard can post alerts to a Slack channel, so your team
sees outages where they already talk. On Free, Slack shows in the channel list with an upgrade
prompt.

## Setting it up

1. In Slack, create an **incoming webhook** for the channel you want alerts in: add the
   "Incoming Webhooks" app to your workspace (or create a Slack app with incoming webhooks
   switched on) and pick the channel. Slack gives you a URL that starts with
   `https://hooks.slack.com/services/`.
2. In PulseGuard, go to **Settings → Alert channels**, add a channel, choose **Slack**, and paste
   the webhook URL.
3. Use **Send test** to check it: a test message should appear in the Slack channel straight away.

Only `https://hooks.slack.com/services/…` URLs are accepted. Treat the webhook URL like a
password: anyone who has it can post to your channel. PulseGuard never shows it in full again.

Slack channels don't need an email-style confirmation: having the webhook URL already proves you
control the channel.

## What a Slack alert looks like

- **🔴 DOWN: Payments API**, with the URL, the cause and when it started.
- **✅ RECOVERED: Payments API**, with how long it was down.

Times show in each reader's own time zone, and each alert has an **Open in PulseGuard** button
that goes to the incident.

## When Slack doesn't answer

If Slack is busy or having problems, PulseGuard tries again later (see **Alert delivery**). If
Slack says the webhook doesn't exist any more, for example because someone removed the app or
the channel was deleted, it isn't retried: create a new webhook, add it as a new Slack channel,
and delete the old one.

## After a downgrade

If you move to Free, Slack channels are switched off, not deleted. After upgrading again, turn
them back on with the channel's switch under **Settings → Alert channels**; the webhook is kept.

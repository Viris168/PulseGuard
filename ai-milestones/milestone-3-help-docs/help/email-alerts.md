---
title: Email alerts
summary: How email alerts work, adding more addresses, confirming them, and sending a test alert.
describes: notification/ChannelService.java, notification/ChannelConfirmationService.java, notification/NotificationChannel.java, notification/AlertMessageFactory.java, frontend/src/pages/settings/ChannelsSection.tsx
---

Email alerts are included on every plan. You get one email when a monitor goes down and one when
it recovers.

## Your account's address

Every account starts with an email alert channel for its own address. It works as soon as you've
**verified your email address** with the link we sent when you signed up. Until then, no alerts
are sent to it.

## Adding more addresses

Under **Settings → Alert channels**, add another email channel for any address: a teammate, or a
team list like `ops@example.com`. Every enabled channel gets every alert.

**New addresses must be confirmed.** PulseGuard emails a confirmation link to the new address, and
nothing is sent to it until someone clicks the link, which stops alerts being pointed at
somebody who didn't ask for them. The link works for 24 hours; if it expires, use **Resend link** on
the channel.

You can have up to 20 alert channels. You can't delete your last one, so you always hear about
outages; use the channel's on/off switch instead.

## What an alert email says

The subject alone tells you what happened, so it reads well on a phone's lock screen:

- `🔴 DOWN: Payments API`, with the URL, the cause (such as "Expected 200 but got 503") and
  when it started.
- `✅ RECOVERED: Payments API`, with how long it was down, from when to when.

Times in emails are in UTC. A URL's query string is shortened to `?…`, so tokens in it aren't
copied into your inbox. The incident page in PulseGuard has the full timeline.

## Sending a test alert

Use **Send test** on a channel to check it works. You get `🔔 Test alert from PulseGuard`
straight away, or an error saying why it couldn't be sent. Test alerts are limited to a few at a
time to stop accidental floods.

## Not getting alerts?

- Check your email address is verified, and that added addresses have been confirmed.
- Check the channel is enabled.
- Look in your spam folder, and add PulseGuard's sending address to your contacts.
- Open the incident page: its timeline shows each alert and whether it was sent or failed.

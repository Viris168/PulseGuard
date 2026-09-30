---
title: Status pages
summary: Publish a public page showing whether your services are up, their uptime history and recent incidents.
describes: statuspage/StatusPageService.java, statuspage/PublicStatusPageService.java, statuspage/PublicStatusAssembler.java, statuspage/dto/StatusPageRequest.java, frontend/src/pages/StatusPageEditor.tsx, frontend/src/pages/public/PublicStatusPage.tsx
---

A status page is a public web page that tells your customers whether your services are working,
without them having to ask. It's available on every plan. Each account has one status page.

## Creating your page

Open **Status page** in the menu and fill in:

- **Title** (up to 80 characters) and an optional **description** (up to 280), for example
  "Acme status" and "Live status of the Acme apps and API".
- **Address**: the last part of the link, 3–40 lowercase letters, numbers or dashes. With
  `acme`, the page is at `/status/acme`. Some words, like `admin` or `api`, are reserved.
- **Monitors on the page**: pick which monitors to show, in which order, and give each a
  **display name** your customers understand, like "Checkout" instead of "prod-api-eu-1". Up to
  100 monitors; monitors you don't pick aren't shown.

Visitors never see your monitors' URLs, headers or ping URLs: only the display names and their
status.

## Draft or published

- **Draft**: only you can edit it, and the public link shows "not found". Use it while you set
  the page up.
- **Published**: anyone with the link can see it. Use **Copy link** to share it, for example in
  your app's footer or help centre.

Changes you save show on the public page straight away; monitor statuses on it refresh at least
once a minute.

## What visitors see

At the top, one overall status:

- **All systems operational**
- **Some systems are recovering**: something was down and is coming back.
- **Partial outage**: some of the shown monitors are down.
- **Major outage**: all of them are down.

Then each monitor with its current status (**Operational**, **Recovering**, **Outage**), a bar
per day showing its uptime, and a list of recent incidents from the last 14 days.

The daily bars cover your plan's history, up to 90 days: 7 days on Free, 90 on Pro and Business.

## Why a failing check doesn't show

A monitor whose latest check failed but that isn't confirmed down yet (**Failing** in your
dashboard) still shows as **Operational** on the status page. Only a confirmed outage, 3 failed
checks in a row, shows as **Outage**. That keeps a one-off network blip from alarming your
customers.

Paused monitors are shown as not monitored and don't count towards the overall status.

---
title: HTTP monitors
summary: Every setting of an HTTP monitor: URL, method, headers, body, expected status codes, interval and timeout.
describes: monitor/dto/MonitorRequestValidator.java, monitor/MonitorHeader.java, check/CheckExecutor.java, common/net/SafeUrlValidator.java, frontend/src/pages/MonitorFormPage.tsx
---

An HTTP monitor calls a URL on a schedule and decides from the answer whether it's up. Use it
for APIs, health endpoints and websites.

## URL and method

The URL must start with `http://` or `https://` and be reachable from the internet; private and
internal addresses such as `localhost` or `192.168.x.x` are refused. Point it at the URL that
answers directly: redirects are not followed.

The method can be `GET` (the default), `HEAD`, `POST` or `PUT`. `HEAD` is lighter when you only
care about the status code. A good target is a dedicated health endpoint, like `/health`, that
checks your app's own dependencies and answers quickly.

## Expected status codes

A check passes when the answer's status code is one you expect. The default is `200`. You can
list up to 10 codes, each between 100 and 599, for example `200` and `204`.

Anything else counts as a failed check, including redirects (`301`, `302`) unless you list them.

## Headers

Add up to 20 request headers, for example an `Authorization` header or an `X-Api-Key` for an
endpoint that needs a login. Header values can be up to 2,000 characters, on one line.

**Secret headers are write-only.** `Authorization`, `Cookie`, and any header whose name contains
auth, token, key, secret, password, session or signature are stored but never shown again, not
even to you. To change one, type the new value.

Some headers are set by PulseGuard and can't be changed: `Host`, `Content-Length`, `Connection`,
`User-Agent` and similar. Every check identifies itself as
`User-Agent: PulseGuard/1.0 (+https://pulseguard.io/bot)`, so you can allow it through a firewall
or bot protection.

## Request body

`POST` and `PUT` checks can send a body of up to 10,000 characters. If you don't set a
`Content-Type` header, PulseGuard sends `application/json` when the body looks like JSON and
plain text otherwise.

## Interval and timeout

- **Interval**: how often the URL is checked. Choose from every minute up to every hour. The
  fastest interval depends on your plan: every 5 minutes on Free, every minute on Pro and
  Business.
- **Timeout**: how long to wait for an answer, from 1 to 30 seconds (10 by default). No answer
  in time counts as a failed check.

A shorter interval tells you about outages sooner: an incident opens after 3 failed checks in a
row, so about 3 minutes with a 1-minute interval and 15 minutes with a 5-minute one.

## Pausing, editing and deleting

- **Pause** stops the checks and keeps the history; **Resume** starts them again.
- **Edit** changes any setting; the next check uses the new ones.
- **Delete** removes the monitor with its checks and incidents. It can't be undone.

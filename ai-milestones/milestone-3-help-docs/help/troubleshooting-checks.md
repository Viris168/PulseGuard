---
title: Troubleshooting failed checks
summary: What each check error means (timeout, DNS, SSL, connection, status mismatch) and how to fix it.
describes: check/CheckExecutor.java, common/net/SafeUrlValidator.java, enumeration/ErrorType.java, monitor/dto/MonitorRequestValidator.java
---

When a check fails, PulseGuard records **why**, and shows it on the monitor's page and on
incidents, for example "STATUS_MISMATCH: Expected 200 but got 503". This article goes through each
kind of error, what usually causes it, and what to do. If your service really was down, the fix
is on your side; if it works fine for you, the causes below are the usual suspects.

## TIMEOUT: no answer in time

PulseGuard got no complete answer within the monitor's timeout (10 seconds by default, at most
30). The message is often "No response".

- The service is overloaded or stuck: check its load, database and logs around that time.
- The endpoint does slow work on every call. Point the monitor at a light health endpoint.
- The service is normally slow, for example a report page. Raise the monitor's timeout.

## DNS: the host name couldn't be found

The domain in the URL didn't resolve: "host does not resolve".

- A typo in the domain, or a domain that expired or was moved.
- A DNS record was deleted or changed during a migration.
- A private name that only works inside your network, such as `api.internal`, can't be resolved
  from the internet. Use a public name, or a heartbeat monitor instead.

## SSL: the certificate couldn't be verified

The HTTPS connection was refused because the certificate isn't valid:

- It **expired**. Renew it, and set up automatic renewal (for example with Let's Encrypt).
- It's **self-signed** or issued by a private authority that browsers don't trust.
- It's for a **different name** than the one in the URL.
- The server sends an **incomplete chain**: some browsers fill in the gap, but other clients
  don't. Configure the server to send the intermediate certificates.

## CONNECTION: the connection failed

PulseGuard couldn't connect, or the connection dropped:

- The server or its port is down, or nothing is listening on it.
- A **firewall** blocks PulseGuard. Every check sends
  `User-Agent: PulseGuard/1.0 (+https://pulseguard.io/bot)`, which you can allow.
- "host resolves to a private or reserved address": the domain now points to an internal address
  (`10.x`, `192.168.x`, `127.0.0.1`…), which PulseGuard never connects to, for security.

## STATUS_MISMATCH: an unexpected status code

The server answered, but not with a code you listed as expected ("Expected 200 but got 503"):

- **401 or 403**: the endpoint needs a login, or a bot protection page is blocking PulseGuard. Add
  the right `Authorization` or API key header to the monitor, or allow PulseGuard's User-Agent.
- **404**: the path is wrong, or the endpoint was moved or removed in a deploy.
- **301, 302, 307, 308**: a redirect. PulseGuard doesn't follow redirects; use the final URL, for
  example `https://` instead of `http://`, or with or without `www`.
- **429**: the service is rate limiting PulseGuard. Check less often, or exempt PulseGuard.
- **500–599**: the service itself failed. See **HTTP status codes** for what each one usually means.

If the code is a normal answer for this endpoint, such as `204` for an empty health check, add it
to the monitor's expected status codes.

## It works in my browser

When a URL loads in your browser but fails in PulseGuard, the difference is usually one of these:

- Your browser is **logged in** (cookies); PulseGuard isn't. Add the login as a header.
- You're on a **network** PulseGuard isn't: an office network, a VPN, or an address that's only
  reachable internally.
- The service **blocks or challenges bots** or certain countries.
- Your browser **followed a redirect** without you noticing.

The **Latest checks** list on the monitor's page shows each recent check's result and error; a
pattern, such as failures at the same time every night, often points to the cause.

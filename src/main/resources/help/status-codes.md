---
title: HTTP status codes
summary: What the common HTTP status codes mean when a monitor reports them, and what to check for each.
describes: check/CheckExecutor.java, monitor/dto/MonitorRequestValidator.java
---

Every HTTP answer carries a three-digit status code. A monitor passes when the code is one you
listed as expected (`200` by default) and fails otherwise, with a message like "Expected 200 but
got 503". Here is what the common codes usually mean for a monitored endpoint.

## 2xx: success

- **200 OK**: the normal answer. The default expected code.
- **201 Created**: usually from a `POST` that created something. Add it to the expected codes for
  a `POST` monitor if that's normal.
- **204 No Content**: success with an empty body. Many health endpoints answer this; add `204`
  to the expected codes.

## 3xx: redirects

PulseGuard doesn't follow redirects, so these fail unless you expect them:

- **301 Moved Permanently** and **308 Permanent Redirect**: the URL has moved for good, for
  example from `http://` to `https://` or to `www`. Update the monitor to the new URL.
- **302 Found** and **307 Temporary Redirect**: often a login redirect, meaning the endpoint needs
  you to be signed in. Add authentication headers, or monitor an endpoint that doesn't need them.
- **304 Not Modified**: a caching answer; unusual for a monitor, and usually a sign of a proxy in
  between.

## 4xx: the request was refused

These mean the server is up, but didn't accept PulseGuard's request:

- **400 Bad Request**: the request is malformed, often a wrong or missing body on a `POST` or
  `PUT` monitor, or a wrong `Content-Type`.
- **401 Unauthorized**: a login is needed. Add an `Authorization` or API key header.
- **403 Forbidden**: the login isn't allowed, or a firewall or bot protection is blocking
  PulseGuard. Allow its User-Agent, `PulseGuard/1.0 (+https://pulseguard.io/bot)`.
- **404 Not Found**: wrong path, or the endpoint was moved or removed in a deploy.
- **405 Method Not Allowed**: the endpoint doesn't accept the monitor's method, for example
  `HEAD`. Switch the monitor to `GET`.
- **408 Request Timeout**: the server gave up waiting; rare, check the server's load.
- **429 Too Many Requests**: the service is rate limiting. Check less often, or exempt PulseGuard.

## 5xx: the server failed

These mean your service, or something in front of it, has a problem:

- **500 Internal Server Error**: the application crashed while answering. Look at its logs for
  that time.
- **502 Bad Gateway**: a proxy or load balancer in front of your app got no valid answer from it.
  Usually the app crashed, is restarting, or was deployed badly.
- **503 Service Unavailable**: the service is overloaded, in maintenance, or has no healthy
  instances behind the load balancer. Health endpoints often return 503 on purpose when a
  dependency, such as the database, is down.
- **504 Gateway Timeout**: the proxy in front of your app waited too long for it. The app is up
  but too slow, often because of a slow database or an external call.

## When the code is normal for you

If an endpoint normally answers with something other than `200`, for example `204`, or `401` for
a check that only needs to know the server is alive, add that code to the monitor's expected
status codes. You can list up to 10.

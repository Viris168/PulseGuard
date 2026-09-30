---
title: API keys
summary: Use PulseGuard from scripts and CI with a personal API key, and what a key can and can't do.
describes: apikey/ApiKeyService.java, apikey/ApiKeySecrets.java, apikey/ApiKeyAuthenticationFilter.java, apikey/dto/ApiKeyRequest.java, auth/SecurityConfig.java, frontend/src/pages/settings/ApiKeysSection.tsx
---

An API key lets a script, a deployment pipeline or another tool use PulseGuard's API as you,
without your password: for example, to pause a monitor during a deploy and resume it after, or
to list open incidents on a wall display. API keys are available on every plan.

## Creating a key

Under **Settings → API keys**, create a key and give it a name (up to 50 characters) that says
what uses it, such as "GitHub Actions deploy". Each name must be unique, and you can have up to
10 keys.

**The key is shown once**, right after you create it. Copy it into your tool's secret settings
straight away; PulseGuard only stores a fingerprint of it and can't show it again. The list
afterwards shows each key's name, its first characters (like `pg_live_Ab12…`) and when it was
last used, or "Never used".

Keys start with `pg_live_`, which lets secret scanners such as GitHub's recognise one that was
committed by mistake.

## Using a key

Send it in the `Authorization` header, like a login token:

```
curl -H "Authorization: Bearer pg_live_…" https://your-pulseguard-address/api/monitors
```

A key can do what you can do with your monitors and incidents, for example:

- `GET /api/monitors`: list monitors and their status;
- `POST /api/monitors/{id}/pause` and `POST /api/monitors/{id}/resume`;
- `GET /api/incidents`: list incidents.

## What a key can't do

For safety, some things need you to be signed in yourself, so a leaked key can't do them:

- create or revoke API keys;
- change your password or email address;
- anything on the Billing page;
- delete your account;
- use **Ask AI** or read AI incident summaries, which cost money per request.

## Revoking a key

Revoke a key from **Settings → API keys** when a tool no longer needs it or you think it has
leaked. It stops working immediately: the next request with it is refused. Create a new key for
the tool if it still needs access.

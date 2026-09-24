# PulseGuard frontend

React + Vite + TypeScript + Tailwind dashboard for PulseGuard.

```bash
npm install
npm run dev     # http://localhost:5173
npm run build
npm run lint
```

## Mock data

The UI runs entirely on **mock data** — nothing talks to the Spring Boot API yet.
Sign in with the demo account (`dev@acme.io` / `password123`, or the "Fill in demo login" button).

Each file in `src/api/` mirrors one backend area; every function is labelled with the endpoint it stands in for:

| File | Stands in for |
|---|---|
| `auth.ts` | `AuthController` (register, login, logout, me, change password) |
| `monitors.ts` | `MonitorController` + stats, checks, heartbeat pings |
| `incidents.ts` | incident list and detail with timeline |
| `channels.ts` | alert channels (`notification_channels`) |
| `billing.ts` | Stripe checkout, customer portal, subscription summary |
| `statusPages.ts` | status page editor + public `/status/{slug}` data |
| `apiKeys.ts` | personal API keys (hash-only storage) |
| `ai.ts` | Ask AI, its access scope and quota, incident summaries |

Supporting files: `mockData.ts` (seed monitors and incidents), `mockDb.ts` (in-memory store,
per-user scoping), `mockHistory.ts` (generated checks, pings, stats), `session.ts` (tokens).

Monitors and incidents reset on reload; accounts, status pages, API keys and Ask AI settings
persist in `localStorage`.

To go live, replace each function body with a `fetch` to the endpoint noted above it. Pages only
depend on those signatures. The Vite dev server already proxies `/api` to `http://localhost:8080`.
Endpoints the backend doesn't have yet are marked "NOT on the backend yet" / "not in
architecture.md yet" in the source.

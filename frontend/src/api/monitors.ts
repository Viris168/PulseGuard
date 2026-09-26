/**
 * Monitor API — MOCK implementation.
 *
 * Every function here matches a route on MonitorController (or one planned in
 * architecture.md). To go live, replace each body with a fetch() to the listed endpoint;
 * the pages only depend on these signatures.
 */
import type { Check, MonitorRangeStats, Ping, StatsRange } from '../types/check'
import type { Monitor, MonitorRequest, MonitorWithStats } from '../types/monitor'
import type { Plan } from '../types/auth'
import { PING_BASE_URL } from '../lib/format'
import { limitsFor } from '../lib/plans'
import { mockAccount } from './auth'
import { ApiError } from './errors'
import type { MockMonitor } from './mockData'
import { db, delay, findMonitor, ownMonitors } from './mockDb'
import { requireUserId } from './session'
import { computeStats, generateChecks, generatePings, type CheckQuery } from './mockHistory'

export { ApiError } from './errors'

/** Plan checks the backend runs in MonitorService via PlanLimits; same 403 messages. */
function currentPlan(): Plan {
  return mockAccount(requireUserId()).plan
}

function enforceInterval(plan: Plan, intervalSeconds: number) {
  const min = limitsFor(plan).minIntervalSeconds
  if (intervalSeconds < min) throw new ApiError(403, `The ${plan} plan requires at least ${min} seconds between checks.`)
}

const RANGE_DAYS: Record<StatsRange, number> = { '24h': 1, '7d': 7, '30d': 30 }

function toMonitor(m: MockMonitor): Monitor {
  const { userId: _u, lastResponseTimeMs: _r, lastStatusCode: _s, recentChecks: _c, baselineMs: _b, failure: _f, heartbeatToken, ...rest } = m
  return { ...structuredClone(rest), pingUrl: heartbeatToken ? `${PING_BASE_URL}/ping/${heartbeatToken}` : null }
}

function newHeartbeatToken(): string {
  const alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  return 'hb_' + Array.from(bytes, (b) => alphabet[b % alphabet.length]).join('')
}

/** GET /api/monitors  (+ uptime/response stats — needs a list-stats endpoint on the backend) */
export async function listMonitors(): Promise<MonitorWithStats[]> {
  await delay()
  return ownMonitors().map((m) => ({
    ...toMonitor(m),
    // Paused monitors have no current 24h figure to show.
    uptime24h: m.isActive ? computeStats(m, db.incidents, '24h').uptimePct : null,
    lastResponseTimeMs: m.lastResponseTimeMs,
    lastStatusCode: m.lastStatusCode,
    recentChecks: [...m.recentChecks],
  }))
}

/** GET /api/monitors/{id} */
export async function getMonitor(id: number): Promise<Monitor> {
  await delay()
  return toMonitor(findMonitor(id))
}

/** GET /api/monitors/{id}/stats?range=24h|7d|30d */
export async function getMonitorStats(id: number, range: StatsRange): Promise<MonitorRangeStats> {
  await delay(450)
  const plan = currentPlan()
  const retention = limitsFor(plan).retentionDays
  if (RANGE_DAYS[range] > retention) {
    throw new ApiError(403, `The ${plan} plan allows at most ${retention} days of history.`)
  }
  // Comparing needs twice the range; Free (7 days) can't compare 7d to the week before.
  return computeStats(findMonitor(id), db.incidents, range, RANGE_DAYS[range] * 2 <= retention)
}

/** GET /api/monitors/{id}/checks?from=&to=&limit=&order=  (newest first by default) */
export async function listChecks(id: number, query: Partial<CheckQuery> = {}): Promise<Check[]> {
  await delay()
  return generateChecks(findMonitor(id), db.incidents, { limit: 20, ...query })
}

/** GET /api/monitors/{id}/pings?from=&to=&limit=&order=  (heartbeats only) */
export async function listPings(id: number, query: Partial<CheckQuery> = {}): Promise<Ping[]> {
  await delay()
  return generatePings(findMonitor(id), db.incidents, { limit: 20, ...query })
}

/**
 * Simulates the user's job calling the ping URL (GET/POST {pingUrl}, public, token-auth).
 * A ping on a down heartbeat resolves its incident straight away.
 */
export async function sendTestPing(id: number): Promise<Monitor> {
  await delay(500)
  const m = findMonitor(id)
  if (m.type !== 'HEARTBEAT') throw new ApiError(400, 'Only heartbeat monitors take pings')
  const now = new Date().toISOString()
  db.incidents = db.incidents.map((i) =>
    i.monitorId === id && i.status === 'OPEN' ? { ...i, status: 'RESOLVED', resolvedAt: now } : i,
  )
  const updated: MockMonitor = { ...m, state: 'UP', lastCheckedAt: now, recentChecks: [...m.recentChecks.slice(-29), true] }
  db.monitors = db.monitors.map((x) => (x.id === id ? updated : x))
  return toMonitor(updated)
}

/** POST /api/monitors */
export async function createMonitor(req: MonitorRequest): Promise<Monitor> {
  await delay(500)
  const plan = currentPlan()
  const max = limitsFor(plan).maxMonitors
  if (ownMonitors().length >= max) throw new ApiError(403, `The ${plan} plan allows at most ${max} monitors.`)
  const heartbeat = req.type === 'HEARTBEAT'
  // The interval floor limits how often *we* call out; a heartbeat's period is the user's own schedule.
  if (!heartbeat) enforceInterval(plan, req.intervalSeconds)
  const now = new Date().toISOString()
  const created: MockMonitor = {
    ...req,
    ...(heartbeat ? { url: '', method: 'GET' as const, expectedStatus: 200, timeoutMs: 0 } : {}),
    graceSeconds: heartbeat ? (req.graceSeconds ?? 300) : null,
    heartbeatToken: heartbeat ? newHeartbeatToken() : null,
    id: db.nextMonitorId++,
    userId: requireUserId(),
    state: 'UP',
    isActive: true,
    lastCheckedAt: null,
    createdAt: now,
    lastResponseTimeMs: null,
    lastStatusCode: null,
    recentChecks: [],
    baselineMs: heartbeat ? 0 : 250,
    failure: heartbeat
      ? { errorType: 'TIMEOUT', statusCode: null, message: 'No ping received' }
      : { errorType: 'CONNECTION', statusCode: null, message: 'Connection refused' },
  }
  db.monitors = [...db.monitors, created]
  return toMonitor(created)
}

/** PUT /api/monitors/{id} */
export async function updateMonitor(id: number, req: MonitorRequest): Promise<Monitor> {
  await delay(500)
  const existing = findMonitor(id)
  if (req.type !== existing.type) throw new ApiError(400, "A monitor's type can't be changed. Create a new monitor instead.")
  if (existing.type === 'HTTP') enforceInterval(currentPlan(), req.intervalSeconds)
  const updated: MockMonitor = {
    ...existing,
    ...req,
    ...(existing.type === 'HEARTBEAT' ? { url: '', graceSeconds: req.graceSeconds ?? existing.graceSeconds } : { graceSeconds: null }),
  }
  db.monitors = db.monitors.map((m) => (m.id === id ? updated : m))
  return toMonitor(updated)
}

/** POST /api/monitors/{id}/pause */
export async function pauseMonitor(id: number): Promise<Monitor> {
  return setActive(id, false)
}

/** POST /api/monitors/{id}/resume */
export async function resumeMonitor(id: number): Promise<Monitor> {
  return setActive(id, true)
}

async function setActive(id: number, isActive: boolean): Promise<Monitor> {
  await delay()
  const updated = { ...findMonitor(id), isActive }
  db.monitors = db.monitors.map((m) => (m.id === id ? updated : m))
  return toMonitor(updated)
}

/** DELETE /api/monitors/{id} */
export async function deleteMonitor(id: number): Promise<void> {
  await delay()
  findMonitor(id)
  db.monitors = db.monitors.filter((m) => m.id !== id)
  // ON DELETE CASCADE
  db.incidents = db.incidents.filter((i) => i.monitorId !== id)
}

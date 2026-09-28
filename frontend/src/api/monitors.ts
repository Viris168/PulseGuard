/**
 * Monitor API — live against MonitorController, StatsController and HeartbeatController.
 *
 * Both monitor types go through the same endpoints. Heartbeats come back with their secret
 * pingUrl (built by the server from PULSEGUARD_PING_BASE_URL), and their pings are listed
 * like checks.
 */
import type { Check, MonitorRangeStats, Ping, StatsRange } from '../types/check'
import type { Monitor, MonitorRequest, MonitorWithStats } from '../types/monitor'
import { api, queryString } from './http'

export { ApiError } from './errors'

/** Options for the check and ping history; mirrors the query parameters of GET /api/monitors/{id}/checks. */
export interface CheckQuery {
  /** Inclusive ISO bounds; omitted = unbounded. */
  from?: string
  to?: string
  limit: number
  order?: 'asc' | 'desc'
}

/** GET /api/monitors — each monitor with uptime24h, last result and recent checks (3 queries server-side). */
export async function listMonitors(): Promise<MonitorWithStats[]> {
  return api<MonitorWithStats[]>('/api/monitors')
}

/** GET /api/monitors/{id} */
export async function getMonitor(id: number): Promise<Monitor> {
  return api<Monitor>(`/api/monitors/${id}`)
}

/** GET /api/monitors/{id}/stats?range=24h|7d|30d — a range beyond the plan's history is a 403. */
export async function getMonitorStats(id: number, range: StatsRange): Promise<MonitorRangeStats> {
  return api<MonitorRangeStats>(`/api/monitors/${id}/stats${queryString({ range })}`)
}

/** GET /api/monitors/{id}/checks?from=&to=&limit=&order=  (newest first by default) */
export async function listChecks(id: number, query: Partial<CheckQuery> = {}): Promise<Check[]> {
  const { from, to, limit = 20, order } = query
  return api<Check[]>(`/api/monitors/${id}/checks${queryString({ from, to, limit, order })}`)
}

/** GET /api/monitors/{id}/pings?from=&to=&limit=&order=  — heartbeats only, newest first by default. */
export async function listPings(id: number, query: Partial<CheckQuery> = {}): Promise<Ping[]> {
  const { from, to, limit = 20, order } = query
  return api<Ping[]>(`/api/monitors/${id}/pings${queryString({ from, to, limit, order })}`)
}

/** POST /api/monitors/{id}/test-ping — records a real ping from you; 409 while paused. */
export async function sendTestPing(id: number): Promise<Monitor> {
  return api<Monitor>(`/api/monitors/${id}/test-ping`, { method: 'POST' })
}

/** POST /api/monitors → 201. Plan limits and SSRF checks run on the server; heartbeats skip both URL rules. */
export async function createMonitor(req: MonitorRequest): Promise<Monitor> {
  return api<Monitor>('/api/monitors', { method: 'POST', body: req })
}

/** PUT /api/monitors/{id} — the type can't change (400 on `type`). */
export async function updateMonitor(id: number, req: MonitorRequest): Promise<Monitor> {
  return api<Monitor>(`/api/monitors/${id}`, { method: 'PUT', body: req })
}

/** POST /api/monitors/{id}/pause */
export async function pauseMonitor(id: number): Promise<Monitor> {
  return api<Monitor>(`/api/monitors/${id}/pause`, { method: 'POST' })
}

/** POST /api/monitors/{id}/resume — a heartbeat gets a full period from now. */
export async function resumeMonitor(id: number): Promise<Monitor> {
  return api<Monitor>(`/api/monitors/${id}/resume`, { method: 'POST' })
}

/** DELETE /api/monitors/{id} → 204 */
export async function deleteMonitor(id: number): Promise<void> {
  await api<void>(`/api/monitors/${id}`, { method: 'DELETE' })
}

/**
 * Monitor API — live against MonitorController and StatsController.
 *
 * Heartbeat monitors (type HEARTBEAT, ping URLs) are not on the backend yet: every monitor
 * the API returns is HTTP, and creating a heartbeat fails with a clear message instead of
 * silently becoming an HTTP monitor.
 */
import type { Check, MonitorRangeStats, Ping, StatsRange } from '../types/check'
import type { Monitor, MonitorRequest, MonitorWithStats } from '../types/monitor'
import { ApiError } from './errors'
import { api, queryString } from './http'

export { ApiError } from './errors'

/** Options for the check history; mirrors the query parameters of GET /api/monitors/{id}/checks. */
export interface CheckQuery {
  /** Inclusive ISO bounds; omitted = unbounded. */
  from?: string
  to?: string
  limit: number
  order?: 'asc' | 'desc'
}

/** MonitorResponse has no heartbeat fields yet; the UI's Monitor type does. */
type MonitorResponse = Omit<Monitor, 'type' | 'graceSeconds' | 'pingUrl'>
type MonitorSummaryResponse = MonitorResponse & Omit<MonitorWithStats, keyof Monitor>

function toMonitor<T extends MonitorResponse>(res: T): T & Pick<Monitor, 'type' | 'graceSeconds' | 'pingUrl'> {
  return { ...res, type: 'HTTP', graceSeconds: null, pingUrl: null }
}

const HEARTBEAT_UNAVAILABLE = "Heartbeat monitors aren't available yet. Use an HTTP monitor."

/** Only the fields MonitorRequest accepts; type and graceSeconds are UI-only for now. */
function toRequest(req: MonitorRequest) {
  if (req.type === 'HEARTBEAT') throw new ApiError(400, HEARTBEAT_UNAVAILABLE, { type: HEARTBEAT_UNAVAILABLE })
  const { name, url, method, expectedStatus, intervalSeconds, timeoutMs } = req
  return { name, url, method, expectedStatus, intervalSeconds, timeoutMs }
}

/** GET /api/monitors — each monitor with uptime24h, last result and recent checks (3 queries server-side). */
export async function listMonitors(): Promise<MonitorWithStats[]> {
  const list = await api<MonitorSummaryResponse[]>('/api/monitors')
  return list.map(toMonitor)
}

/** GET /api/monitors/{id} */
export async function getMonitor(id: number): Promise<Monitor> {
  return toMonitor(await api<MonitorResponse>(`/api/monitors/${id}`))
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

/** Heartbeat pings: no heartbeat monitors exist on the backend yet, so there are none. */
export async function listPings(_id: number, _query: Partial<CheckQuery> = {}): Promise<Ping[]> {
  return []
}

/** Heartbeat-only; see listPings. */
export async function sendTestPing(_id: number): Promise<Monitor> {
  throw new ApiError(400, HEARTBEAT_UNAVAILABLE)
}

/** POST /api/monitors → 201. Plan limits and SSRF checks run on the server. */
export async function createMonitor(req: MonitorRequest): Promise<Monitor> {
  return toMonitor(await api<MonitorResponse>('/api/monitors', { method: 'POST', body: toRequest(req) }))
}

/** PUT /api/monitors/{id} */
export async function updateMonitor(id: number, req: MonitorRequest): Promise<Monitor> {
  return toMonitor(await api<MonitorResponse>(`/api/monitors/${id}`, { method: 'PUT', body: toRequest(req) }))
}

/** POST /api/monitors/{id}/pause */
export async function pauseMonitor(id: number): Promise<Monitor> {
  return toMonitor(await api<MonitorResponse>(`/api/monitors/${id}/pause`, { method: 'POST' }))
}

/** POST /api/monitors/{id}/resume */
export async function resumeMonitor(id: number): Promise<Monitor> {
  return toMonitor(await api<MonitorResponse>(`/api/monitors/${id}/resume`, { method: 'POST' }))
}

/** DELETE /api/monitors/{id} → 204 */
export async function deleteMonitor(id: number): Promise<void> {
  await api<void>(`/api/monitors/${id}`, { method: 'DELETE' })
}

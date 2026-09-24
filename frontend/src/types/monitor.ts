// Mirrors com.viris.PulseGuard.enumeration.MonitorState
export type MonitorState = 'UP' | 'SUSPICIOUS' | 'DOWN' | 'RECOVERING'

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'HEAD'

/**
 * HTTP: PulseGuard calls your URL on a schedule.
 * HEARTBEAT: your job calls PulseGuard's ping URL; silence past the deadline means down.
 * (Not on the backend's Monitor entity yet.)
 */
export type MonitorType = 'HTTP' | 'HEARTBEAT'

// Mirrors com.viris.PulseGuard.monitor.dto.MonitorResponse (Instants arrive as ISO strings)
export interface Monitor {
  id: number
  type: MonitorType
  name: string
  url: string
  method: HttpMethod
  expectedStatus: number
  intervalSeconds: number
  timeoutMs: number
  state: MonitorState
  isActive: boolean
  /** For heartbeats: when the last ping arrived. */
  lastCheckedAt: string | null
  createdAt: string
  /** Heartbeat only: extra time after the expected ping before it counts as missed. */
  graceSeconds: number | null
  /** Heartbeat only: the secret URL the job calls. Treat like a password. */
  pingUrl: string | null
}

// Mirrors com.viris.PulseGuard.monitor.dto.MonitorRequest
export interface MonitorRequest {
  type: MonitorType
  name: string
  url: string
  method: HttpMethod
  expectedStatus: number
  intervalSeconds: number
  timeoutMs: number
  /** Heartbeat only. */
  graceSeconds?: number
}

// Not in the backend yet — dashboard stats will need a new endpoint (e.g. GET /api/monitors/stats).
export interface MonitorStats {
  uptime24h: number | null
  lastResponseTimeMs: number | null
  lastStatusCode: number | null
  /** Most recent checks, oldest first; true = success. Drives the mini status bar. */
  recentChecks: boolean[]
}

export type MonitorWithStats = Monitor & MonitorStats

/** What the UI shows: paused wins, then "waiting" until the first check or ping. */
export type DisplayStatus = MonitorState | 'PAUSED' | 'PENDING'

export function displayStatus(m: Pick<Monitor, 'state' | 'isActive' | 'lastCheckedAt'>): DisplayStatus {
  if (!m.isActive) return 'PAUSED'
  if (m.lastCheckedAt === null) return 'PENDING'
  return m.state
}

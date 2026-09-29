// Mirrors com.viris.PulseGuard.enumeration.MonitorState
export type MonitorState = 'UP' | 'SUSPICIOUS' | 'DOWN' | 'RECOVERING'

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'HEAD'

/**
 * HTTP: PulseGuard calls your URL on a schedule.
 * HEARTBEAT: your job calls PulseGuard's ping URL; silence past the deadline means down.
 * Mirrors com.viris.PulseGuard.enumeration.MonitorType.
 */
export type MonitorType = 'HTTP' | 'HEARTBEAT'

// Mirrors com.viris.PulseGuard.monitor.dto.MonitorResponse (Instants arrive as ISO strings)
export interface Monitor {
  id: number
  type: MonitorType
  name: string
  url: string
  method: HttpMethod
  /** The first of expectedStatuses; kept for older code. */
  expectedStatus: number
  /** Any of these counts as up. */
  expectedStatuses: number[]
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
  /** HTTP only. Not in the monitors list, only on a single monitor. */
  headers?: MonitorHeader[]
  /** HTTP POST/PUT only. Not in the monitors list. */
  requestBody?: string | null
  /** True when read with an API key: the body exists but is write-only for keys. */
  requestBodyHidden?: boolean
}

/** A saved request header. A secret one (Authorization, *-Token, *-Key…) never comes back with its value. */
export interface MonitorHeader {
  name: string
  value: string | null
  secret: boolean
}

// Mirrors com.viris.PulseGuard.monitor.dto.MonitorRequest
export interface MonitorRequest {
  type: MonitorType
  name: string
  url: string
  method: HttpMethod
  expectedStatuses: number[]
  intervalSeconds: number
  timeoutMs: number
  /** Heartbeat only. */
  graceSeconds?: number
  /** A null value keeps the value already saved under that name (how secrets round-trip). */
  headers?: { name: string; value: string | null }[]
  /** POST and PUT only. */
  requestBody?: string
}

// The per-monitor figures GET /api/monitors adds to each monitor (MonitorSummaryResponse).
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

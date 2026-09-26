// Mirrors com.viris.PulseGuard.enumeration.CheckResult / ErrorType
export type CheckResult = 'UP' | 'DOWN'
export type ErrorType = 'TIMEOUT' | 'DNS' | 'SSL' | 'CONNECTION' | 'STATUS_MISMATCH'

// One row of the `checks` table — GET /api/monitors/{id}/checks
export interface Check {
  id: number
  monitorId: number
  result: CheckResult
  statusCode: number | null
  responseTimeMs: number | null
  errorType: ErrorType | null
  errorMessage: string | null
  checkedAt: string
}

export type StatsRange = '24h' | '7d' | '30d'

export interface SeriesPoint {
  start: string
  end: string
  /** null = no successful checks in this bucket (fully down, or no data). */
  avgResponseMs: number | null
  /** null = monitor wasn't checking during this bucket. */
  uptimePct: number | null
}

export interface UptimeBucket {
  start: string
  end: string
  uptimePct: number | null
  downtimeSeconds: number
}

// GET /api/monitors/{id}/stats?range=24h|7d|30d
export interface MonitorRangeStats {
  range: StatsRange
  uptimePct: number | null
  avgResponseMs: number | null
  p95ResponseMs: number | null
  checksCount: number
  incidentCount: number
  downtimeSeconds: number
  /** Fine-grained buckets for the response-time chart. */
  responseSeries: SeriesPoint[]
  /** Coarser buckets for the uptime strip. */
  uptimeBuckets: UptimeBucket[]
  /** Same-length period just before this one; null when the plan's history doesn't reach it. */
  previous: PeriodSummary | null
}

export interface PeriodSummary {
  uptimePct: number | null
  avgResponseMs: number | null
  p95ResponseMs: number | null
  incidentCount: number
  downtimeSeconds: number
}

// One received heartbeat ping — GET /api/monitors/{id}/pings (not in architecture.md yet)
export interface Ping {
  id: number
  receivedAt: string
  sourceIp: string
}

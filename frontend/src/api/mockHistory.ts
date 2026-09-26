/**
 * Generates check history and range stats for the mock API.
 *
 * Everything is derived from the monitor row + its incidents, so the dashboard, the chart,
 * the uptime strip and the checks table all agree. Randomness is seeded by monitor id and
 * time bucket, so numbers stay put between re-renders and range switches.
 */
import type { Check, ErrorType, MonitorRangeStats, Ping, SeriesPoint, StatsRange, UptimeBucket } from '../types/check'
import type { IncidentEvent } from '../types/incident'
import { failedDeliveries, INCIDENT_THRESHOLDS, seedChannels, type MockIncident, type MockMonitor } from './mockData'

const MIN = 60_000
const HOUR = 60 * MIN
const DAY = 24 * HOUR

const RANGES: Record<StatsRange, { span: number; seriesBucket: number; uptimeBucket: number }> = {
  '24h': { span: DAY, seriesBucket: 15 * MIN, uptimeBucket: HOUR },
  '7d': { span: 7 * DAY, seriesBucket: HOUR, uptimeBucket: 6 * HOUR },
  '30d': { span: 30 * DAY, seriesBucket: 4 * HOUR, uptimeBucket: DAY },
}

/** mulberry32, one draw per seed. */
function rand(seed: number): number {
  let t = (seed + 0x6d2b79f5) | 0
  t = Math.imul(t ^ (t >>> 15), t | 1)
  t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
  return ((t ^ (t >>> 14)) >>> 0) / 4294967296
}

const seedFor = (monitorId: number, t: number, salt = 0) => monitorId * 1_000_003 + Math.floor(t / MIN) + salt

const overlap = (a0: number, a1: number, b0: number, b1: number) => Math.max(0, Math.min(a1, b1) - Math.max(a0, b0))

/** The span of time this monitor was actually being checked. */
function checkingWindow(m: MockMonitor, now: number): [number, number] {
  const start = Date.parse(m.createdAt)
  if (m.lastCheckedAt === null) return [start, start]
  return [start, m.isActive ? now : Date.parse(m.lastCheckedAt)]
}

function downtimeBetween(incidents: MockIncident[], t0: number, t1: number, now: number): number {
  return incidents.reduce(
    (sum, i) => sum + overlap(t0, t1, Date.parse(i.startedAt), i.resolvedAt ? Date.parse(i.resolvedAt) : now),
    0,
  )
}

interface RawBucket {
  start: number
  end: number
  covered: number
  down: number
}

function bucketize(m: MockMonitor, incidents: MockIncident[], span: number, size: number, now: number): RawBucket[] {
  const [winStart, winEnd] = checkingWindow(m, now)
  // Align to bucket boundaries so buckets (and their seeded noise) don't shift every call.
  const end = Math.ceil(now / size) * size
  const count = Math.ceil(span / size)
  return Array.from({ length: count }, (_, i) => {
    const start = end - (count - i) * size
    const from = Math.max(start, winStart)
    const to = Math.min(start + size, winEnd, now)
    const covered = Math.max(0, to - from)
    return { start, end: start + size, covered, down: covered ? downtimeBetween(incidents, from, to, now) : 0 }
  })
}

function responseFor(m: MockMonitor, b: RawBucket): number | null {
  // Heartbeats are pinged by the user's job; there's no request of ours to time.
  if (m.type === 'HEARTBEAT' || b.covered === 0 || b.down >= b.covered) return null
  const hour = new Date(b.start).getHours()
  const daily = 1 + 0.18 * Math.sin(((hour - 8) / 24) * 2 * Math.PI) // busier afternoons
  const noise = 0.8 + 0.4 * rand(seedFor(m.id, b.start))
  const spike = rand(seedFor(m.id, b.start, 7)) > 0.97 ? 1.8 : 1
  const degraded = b.down > 0 ? 1.7 : 1
  return Math.round(m.baselineMs * daily * noise * spike * degraded)
}

const iso = (t: number) => new Date(t).toISOString()
const pct = (covered: number, down: number) => (covered ? 100 * (1 - down / covered) : null)

/**
 * Stats for the range ending now. With `withPrevious`, also the same-length period before it,
 * for the "vs previous period" deltas (callers only ask when the plan keeps that much history).
 */
export function computeStats(
  m: MockMonitor,
  allIncidents: MockIncident[],
  range: StatsRange,
  withPrevious = false,
): MonitorRangeStats {
  const now = Date.now()
  const current = summarize(m, allIncidents, range, now)
  if (!withPrevious) return { ...current, previous: null }
  const prev = summarize(m, allIncidents, range, now - RANGES[range].span)
  return {
    ...current,
    previous:
      prev.checksCount === 0
        ? null
        : {
            uptimePct: prev.uptimePct,
            avgResponseMs: prev.avgResponseMs,
            p95ResponseMs: prev.p95ResponseMs,
            incidentCount: prev.incidentCount,
            downtimeSeconds: prev.downtimeSeconds,
          },
  }
}

function summarize(m: MockMonitor, allIncidents: MockIncident[], range: StatsRange, now: number): Omit<MonitorRangeStats, 'previous'> {
  const cfg = RANGES[range]
  const incidents = allIncidents.filter((i) => i.monitorId === m.id)

  const series = bucketize(m, incidents, cfg.span, cfg.seriesBucket, now)
  const responseSeries: SeriesPoint[] = series.map((b) => ({
    start: iso(b.start),
    end: iso(b.end),
    avgResponseMs: responseFor(m, b),
    uptimePct: pct(b.covered, b.down),
  }))

  const uptimeBuckets: UptimeBucket[] = bucketize(m, incidents, cfg.span, cfg.uptimeBucket, now).map((b) => ({
    start: iso(b.start),
    end: iso(b.end),
    uptimePct: pct(b.covered, b.down),
    downtimeSeconds: Math.round(b.down / 1000),
  }))

  const covered = series.reduce((s, b) => s + b.covered, 0)
  const down = series.reduce((s, b) => s + b.down, 0)
  const values = responseSeries.flatMap((p) => (p.avgResponseMs === null ? [] : [p.avgResponseMs])).sort((a, b) => a - b)
  const rangeStart = series[0].start

  return {
    range,
    uptimePct: pct(covered, down),
    avgResponseMs: values.length ? Math.round(values.reduce((s, v) => s + v, 0) / values.length) : null,
    // Bucket averages flatten the tail, so stretch the 95th bucket a little to approximate per-check p95.
    p95ResponseMs: values.length ? Math.round(values[Math.floor(0.95 * (values.length - 1))] * 1.25) : null,
    checksCount: Math.round(covered / (m.intervalSeconds * 1000)),
    incidentCount: incidents.filter(
      (i) => overlap(rangeStart, now, Date.parse(i.startedAt), i.resolvedAt ? Date.parse(i.resolvedAt) : now) > 0,
    ).length,
    downtimeSeconds: Math.round(down / 1000),
    responseSeries,
    uptimeBuckets,
  }
}

function guessErrorType(cause: string): ErrorType {
  if (/timed out/i.test(cause)) return 'TIMEOUT'
  if (/dns/i.test(cause)) return 'DNS'
  if (/ssl|tls|certificate/i.test(cause)) return 'SSL'
  if (/expected \d+, got/i.test(cause)) return 'STATUS_MISMATCH'
  return 'CONNECTION'
}

export interface CheckQuery {
  /** Inclusive ISO bounds; omitted = unbounded. */
  from?: string
  to?: string
  limit: number
  order?: 'asc' | 'desc'
}

/**
 * Checks on this monitor's schedule grid (anchored at lastCheckedAt), filtered to the query window.
 * The newest results come from the seed's recentChecks; older ones fail only inside incidents.
 */
export function generateChecks(m: MockMonitor, allIncidents: MockIncident[], q: CheckQuery): Check[] {
  // Heartbeats have pings, not checks — see generatePings.
  if (m.lastCheckedAt === null || m.type === 'HEARTBEAT') return []
  const now = Date.now()
  const last = Date.parse(m.lastCheckedAt)
  const interval = m.intervalSeconds * 1000
  const from = Math.max(Date.parse(m.createdAt), q.from ? Date.parse(q.from) : -Infinity)
  const to = Math.min(last, q.to ? Date.parse(q.to) : Infinity)
  if (to < from) return []

  // k = how many intervals before the latest check.
  const kNewest = Math.ceil((last - to) / interval)
  const kOldest = Math.floor((last - from) / interval)
  const count = Math.min(q.limit, kOldest - kNewest + 1)
  const ks = Array.from({ length: Math.max(0, count) }, (_, n) => (q.order === 'asc' ? kOldest - n : kNewest + n))

  const incidents = allIncidents.filter((i) => i.monitorId === m.id)
  // A resolved incident's last `recovery` checks were passes, so failures stop one interval before resolve.
  const failingUntil = (i: MockIncident) =>
    i.resolvedAt ? Date.parse(i.resolvedAt) - (INCIDENT_THRESHOLDS.recovery - 1) * interval : now

  return ks.map((k) => {
    const t = last - k * interval
    const r = rand(seedFor(m.id, t, 13))
    const recentIdx = m.recentChecks.length - 1 - k
    const inIncident = incidents.find((x) => Date.parse(x.startedAt) <= t && t < failingUntil(x))
    const ok = recentIdx >= 0 ? m.recentChecks[recentIdx] : !inIncident
    const base = { id: Math.floor(t / 1000), monitorId: m.id, checkedAt: iso(t) }

    if (ok) {
      return {
        ...base,
        result: 'UP' as const,
        statusCode: m.expectedStatus,
        responseTimeMs: k === 0 && m.lastResponseTimeMs !== null ? m.lastResponseTimeMs : Math.round(m.baselineMs * (0.8 + 0.4 * r)),
        errorType: null,
        errorMessage: null,
      }
    }

    // Recent failures use the monitor's current failure mode; historical ones follow their incident.
    const errorMessage = recentIdx >= 0 || !inIncident ? m.failure.message : inIncident.cause
    const errorType = recentIdx >= 0 || !inIncident ? m.failure.errorType : guessErrorType(inIncident.cause)
    const statusMatch = /got (\d{3})/.exec(errorMessage)
    return {
      ...base,
      result: 'DOWN' as const,
      statusCode: statusMatch ? Number(statusMatch[1]) : null,
      responseTimeMs:
        errorType === 'TIMEOUT'
          ? m.timeoutMs
          : errorType === 'STATUS_MISMATCH'
            ? k === 0 && m.lastResponseTimeMs !== null
              ? m.lastResponseTimeMs
              : Math.round(m.baselineMs * (0.5 + 0.3 * r))
            : null,
      errorType,
      errorMessage,
    }
  })
}

/**
 * Pings on the job's schedule (anchored at the last ping), minus the ones missed during
 * incidents, plus the ping that ended each incident. Newest first unless order = 'asc'.
 */
export function generatePings(m: MockMonitor, allIncidents: MockIncident[], q: CheckQuery): Ping[] {
  if (m.type !== 'HEARTBEAT' || m.lastCheckedAt === null) return []
  const now = Date.now()
  const last = Date.parse(m.lastCheckedAt)
  const interval = m.intervalSeconds * 1000
  const grace = (m.graceSeconds ?? 0) * 1000
  const from = Math.max(Date.parse(m.createdAt), q.from ? Date.parse(q.from) : -Infinity)
  const to = Math.min(now, q.to ? Date.parse(q.to) : Infinity)
  const incidents = allIncidents.filter((i) => i.monitorId === m.id)
  // An incident starts at deadline = expected ping + grace, so the missed ping was due `grace` earlier.
  const missed = (t: number) =>
    incidents.some((i) => Date.parse(i.startedAt) - grace <= t && t < (i.resolvedAt ? Date.parse(i.resolvedAt) : now))

  const times: number[] = []
  for (let t = Math.min(last, to); t >= from && times.length < 500; t -= interval) {
    if (t <= last && !missed(t)) times.push(t)
  }
  for (const i of incidents) {
    const r = i.resolvedAt ? Date.parse(i.resolvedAt) : null
    if (r !== null && r >= from && r <= to) times.push(r)
  }
  const sorted = [...new Set(times)].sort((a, b) => (q.order === 'asc' ? a - b : b - a)).slice(0, q.limit)
  return sorted.map((t) => {
    const r = rand(seedFor(m.id, t, 29))
    // Cron jobs rarely land on the exact second.
    const at = t + Math.round((r - 0.5) * 8000)
    return { id: Math.floor(t / 1000), receivedAt: iso(at), sourceIp: `203.0.113.${10 + Math.floor(r * 40)}` }
  })
}

/** Rebuilds what the backend would log for an incident: the failure streak, alerts, and recovery. */
export function buildTimeline(m: MockMonitor, i: MockIncident): IncidentEvent[] {
  if (m.type === 'HEARTBEAT') return buildHeartbeatTimeline(m, i)
  const interval = m.intervalSeconds * 1000
  const now = Date.now()
  const started = Date.parse(i.startedAt)
  const opened = started + (INCIDENT_THRESHOLDS.failure - 1) * interval
  const events: IncidentEvent[] = [{ type: 'CHECK_FAILED', at: iso(started), detail: i.cause }]

  const notify = (event: 'OPENED' | 'RESOLVED', at: number) => notifyAll(i, event, at)

  if (opened <= now) {
    events.push({ type: 'OPENED', at: iso(opened), failedChecks: INCIDENT_THRESHOLDS.failure }, ...notify('OPENED', opened))
  }

  if (i.resolvedAt) {
    const resolved = Date.parse(i.resolvedAt)
    events.push(
      { type: 'CHECK_PASSED', at: iso(resolved - (INCIDENT_THRESHOLDS.recovery - 1) * interval) },
      { type: 'RESOLVED', at: iso(resolved), passedChecks: INCIDENT_THRESHOLDS.recovery },
      ...notify('RESOLVED', resolved),
    )
  } else if (m.state === 'RECOVERING' && m.lastCheckedAt) {
    events.push({ type: 'CHECK_PASSED', at: m.lastCheckedAt })
  }

  return events
}

/** One bucket per day for the last `days` days, oldest first — for the public status page. */
export function dailyUptime(m: MockMonitor, allIncidents: MockIncident[], days: number): { buckets: UptimeBucket[]; uptimePct: number | null } {
  const now = Date.now()
  const incidents = allIncidents.filter((i) => i.monitorId === m.id)
  const raw = bucketize(m, incidents, days * DAY, DAY, now)
  const covered = raw.reduce((s, b) => s + b.covered, 0)
  const down = raw.reduce((s, b) => s + b.down, 0)
  return {
    buckets: raw.map((b) => ({ start: iso(b.start), end: iso(b.end), uptimePct: pct(b.covered, b.down), downtimeSeconds: Math.round(b.down / 1000) })),
    uptimePct: pct(covered, down),
  }
}

function notifyAll(i: MockIncident, event: 'OPENED' | 'RESOLVED', at: number): IncidentEvent[] {
  return seedChannels.map((c, n) => {
    const error = failedDeliveries[`${i.id}:${c.id}:${event}`]
    return {
      type: 'NOTIFIED',
      // Alerts go out after commit, a moment after the transition.
      at: iso(at + 1500 + n * 400),
      event,
      channel: c.type,
      target: c.target,
      status: error ? 'FAILED' : 'SENT',
      ...(error ? { error } : {}),
    }
  })
}

/** Heartbeats: the deadline passes → down at once (no 3-strike rule); the next ping resolves it. */
function buildHeartbeatTimeline(m: MockMonitor, i: MockIncident): IncidentEvent[] {
  const grace = (m.graceSeconds ?? 0) * 1000
  const opened = Date.parse(i.startedAt)
  const events: IncidentEvent[] = [
    { type: 'CHECK_FAILED', at: iso(opened - grace), detail: 'The expected ping did not arrive.' },
    { type: 'OPENED', at: iso(opened), failedChecks: 0 },
    ...notifyAll(i, 'OPENED', opened),
  ]
  if (i.resolvedAt) {
    const resolved = Date.parse(i.resolvedAt)
    events.push(
      { type: 'CHECK_PASSED', at: iso(resolved) },
      { type: 'RESOLVED', at: iso(resolved), passedChecks: 1 },
      ...notifyAll(i, 'RESOLVED', resolved),
    )
  }
  return events
}

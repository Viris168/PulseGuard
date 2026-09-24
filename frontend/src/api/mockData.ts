import type { ErrorType } from '../types/check'
import type { ChannelType, IncidentStatus } from '../types/incident'
import type { Monitor } from '../types/monitor'

/** What the mock "database" stores per monitor: the real row plus knobs for generating history. */
export interface MockMonitor extends Omit<Monitor, 'pingUrl'> {
  /** Owner — every query is scoped to the signed-in user, like the backend. */
  userId: number
  /** Heartbeat secret; the public ping URL is built from it. */
  heartbeatToken: string | null
  lastResponseTimeMs: number | null
  lastStatusCode: number | null
  /** Latest checks, oldest first; true = success. Must agree with `state` and any open incident. */
  recentChecks: boolean[]
  /** Typical healthy response time, used to generate chart data. */
  baselineMs: number
  /** What a failed check on this monitor looks like. */
  failure: { errorType: ErrorType; statusCode: number | null; message: string }
}

export interface MockIncident {
  id: number
  monitorId: number
  status: IncidentStatus
  cause: string
  startedAt: string
  resolvedAt: string | null
}

const MIN = 60_000
const HOUR = 60 * MIN
const DAY = 24 * HOUR
const ago = (ms: number) => new Date(Date.now() - ms).toISOString()

function history(length: number, failAt: number[] = []): boolean[] {
  return Array.from({ length }, (_, i) => !failAt.includes(i))
}

export const seedMonitors: MockMonitor[] = [
  {
    id: 1,
    userId: 1,
    type: 'HTTP',
    graceSeconds: null,
    heartbeatToken: null,
    name: 'Production API',
    url: 'https://api.acme.io/health',
    method: 'GET',
    expectedStatus: 200,
    intervalSeconds: 60,
    timeoutMs: 5000,
    state: 'UP',
    isActive: true,
    lastCheckedAt: ago(0.5 * MIN),
    createdAt: ago(42 * DAY),
    lastResponseTimeMs: 142,
    lastStatusCode: 200,
    recentChecks: history(30),
    baselineMs: 140,
    failure: { errorType: 'CONNECTION', statusCode: null, message: 'Connection refused' },
  },
  {
    id: 2,
    userId: 1,
    type: 'HTTP',
    graceSeconds: null,
    heartbeatToken: null,
    name: 'Marketing site',
    url: 'https://acme.io',
    method: 'HEAD',
    expectedStatus: 200,
    intervalSeconds: 300,
    timeoutMs: 10000,
    state: 'UP',
    isActive: true,
    lastCheckedAt: ago(3 * MIN),
    createdAt: ago(30 * DAY),
    lastResponseTimeMs: 388,
    lastStatusCode: 200,
    // One transient blip — not enough to open an incident.
    recentChecks: history(30, [11]),
    baselineMs: 380,
    failure: { errorType: 'TIMEOUT', statusCode: null, message: 'Timed out after 10000 ms' },
  },
  {
    id: 3,
    userId: 1,
    type: 'HTTP',
    graceSeconds: null,
    heartbeatToken: null,
    name: 'Payments webhook',
    url: 'https://pay.acme.io/webhooks/stripe',
    method: 'POST',
    expectedStatus: 200,
    intervalSeconds: 60,
    timeoutMs: 8000,
    state: 'DOWN',
    isActive: true,
    lastCheckedAt: ago(1 * MIN),
    createdAt: ago(12 * DAY),
    lastResponseTimeMs: 118,
    lastStatusCode: 503,
    recentChecks: history(30, [25, 26, 27, 28, 29]),
    baselineMs: 210,
    failure: { errorType: 'STATUS_MISMATCH', statusCode: 503, message: 'Expected 200, got 503' },
  },
  {
    id: 4,
    userId: 1,
    type: 'HTTP',
    graceSeconds: null,
    heartbeatToken: null,
    name: 'Auth service',
    url: 'https://auth.acme.io/actuator/health',
    method: 'GET',
    expectedStatus: 200,
    intervalSeconds: 120,
    timeoutMs: 5000,
    state: 'SUSPICIOUS',
    isActive: true,
    lastCheckedAt: ago(2 * MIN),
    createdAt: ago(20 * DAY),
    lastResponseTimeMs: 5000,
    lastStatusCode: null,
    // Two failures in a row: suspicious, but under the 3-failure incident threshold.
    recentChecks: history(30, [28, 29]),
    baselineMs: 320,
    failure: { errorType: 'TIMEOUT', statusCode: null, message: 'Timed out after 5000 ms' },
  },
  {
    id: 5,
    userId: 1,
    type: 'HTTP',
    graceSeconds: null,
    heartbeatToken: null,
    name: 'Search API',
    url: 'https://search.acme.io/v2/ping',
    method: 'GET',
    expectedStatus: 204,
    intervalSeconds: 300,
    timeoutMs: 10000,
    state: 'RECOVERING',
    isActive: true,
    lastCheckedAt: ago(4 * MIN),
    createdAt: ago(8 * DAY),
    lastResponseTimeMs: 231,
    lastStatusCode: 204,
    // Down for 5 checks, first pass just now — incident stays open until 2 passes.
    recentChecks: history(30, [24, 25, 26, 27, 28]),
    baselineMs: 230,
    failure: { errorType: 'DNS', statusCode: null, message: 'DNS resolution failed for search.acme.io' },
  },
  {
    id: 6,
    userId: 1,
    type: 'HTTP',
    graceSeconds: null,
    heartbeatToken: null,
    name: 'Staging API',
    url: 'https://staging.acme.io/health',
    method: 'GET',
    expectedStatus: 200,
    intervalSeconds: 900,
    timeoutMs: 10000,
    state: 'UP',
    isActive: false,
    lastCheckedAt: ago(2 * DAY),
    createdAt: ago(60 * DAY),
    lastResponseTimeMs: null,
    lastStatusCode: null,
    recentChecks: [],
    baselineMs: 520,
    failure: { errorType: 'STATUS_MISMATCH', statusCode: 500, message: 'Expected 200, got 500' },
  },
  {
    id: 7,
    userId: 1,
    type: 'HEARTBEAT',
    name: 'Nightly backup',
    url: '',
    method: 'GET',
    expectedStatus: 200,
    intervalSeconds: 86_400,
    graceSeconds: 3600,
    heartbeatToken: 'hb_7Qm2xK9pLr4vT8sW',
    timeoutMs: 0,
    state: 'UP',
    isActive: true,
    lastCheckedAt: ago(3 * HOUR),
    createdAt: ago(40 * DAY),
    lastResponseTimeMs: null,
    lastStatusCode: null,
    recentChecks: history(30, [25]),
    baselineMs: 0,
    failure: { errorType: 'TIMEOUT', statusCode: null, message: 'No ping received' },
  },
  {
    id: 8,
    userId: 1,
    type: 'HEARTBEAT',
    name: 'Invoice sync job',
    url: '',
    method: 'GET',
    expectedStatus: 200,
    intervalSeconds: 3600,
    graceSeconds: 600,
    heartbeatToken: 'hb_Zc4nB8yHq1mR6dFj',
    timeoutMs: 0,
    // Last ping 2h ago, due every 1h + 10m grace → overdue for 50m.
    state: 'DOWN',
    isActive: true,
    lastCheckedAt: ago(2 * HOUR),
    createdAt: ago(15 * DAY),
    lastResponseTimeMs: null,
    lastStatusCode: null,
    recentChecks: history(30, [29]),
    baselineMs: 0,
    failure: { errorType: 'TIMEOUT', statusCode: null, message: 'No ping received' },
  },
]

const incident = (
  id: number,
  monitorId: number,
  cause: string,
  startedAgoMs: number,
  durationMs: number | null,
): MockIncident => ({
  id,
  monitorId,
  cause,
  status: durationMs === null ? 'OPEN' : 'RESOLVED',
  startedAt: ago(startedAgoMs),
  resolvedAt: durationMs === null ? null : ago(startedAgoMs - durationMs),
})

export const seedIncidents: MockIncident[] = [
  incident(1, 1, 'Connection refused', 9 * DAY, 14 * MIN),
  incident(2, 1, 'Timed out after 5000 ms', 23 * DAY + 5 * HOUR, 6 * MIN),
  incident(3, 2, 'Expected 200, got 502', 4 * DAY + 2 * HOUR, 25 * MIN),
  incident(4, 3, 'Expected 200, got 503', 5 * MIN, null),
  incident(5, 3, 'Expected 200, got 503', 2 * DAY + 7 * HOUR, 42 * MIN),
  incident(6, 3, 'SSL handshake failed: certificate expired', 10 * DAY + 3 * HOUR, 2 * HOUR + 8 * MIN),
  incident(7, 4, 'Timed out after 5000 ms', 12 * DAY + 1 * HOUR, 70 * MIN),
  incident(8, 5, 'DNS resolution failed for search.acme.io', 30 * MIN, null),
  incident(9, 5, 'Connection reset by peer', 6 * DAY + 9 * HOUR, 3 * HOUR),
  incident(10, 6, 'Expected 200, got 500', 20 * DAY, 2 * HOUR),
  incident(11, 8, 'No ping received within 1h (+10m grace)', 50 * MIN, null),
  incident(12, 7, 'No ping received within 1d (+1h grace)', 5 * DAY + 1 * HOUR, 2 * HOUR + 30 * MIN),
]

/** Rows of `notification_channels` for the mock user. */
export const seedChannels: { id: number; type: ChannelType; target: string }[] = [
  { id: 1, type: 'EMAIL', target: 'dev@acme.io' },
  { id: 2, type: 'SLACK', target: '#ops-alerts' },
]

/** Deliveries that failed, keyed by `${incidentId}:${channelId}:${event}`; everything else was SENT. */
export const failedDeliveries: Record<string, string> = {
  '6:2:OPENED': 'Slack webhook returned 404 (channel not found)',
}

/** Mirrors pulseguard.incident.* in application.yaml. */
export const INCIDENT_THRESHOLDS = { failure: 3, recovery: 2 }

/** Realistic API payloads for component tests. Override any field per test. */
import type { User } from '../types/auth'
import type { BillingSummary } from '../types/billing'
import type { Check, MonitorRangeStats } from '../types/check'
import type { Incident, IncidentDetail } from '../types/incident'
import type { Monitor, MonitorWithStats } from '../types/monitor'

export const NOW = '2026-01-01T12:00:00Z'
const minutesAgo = (m: number) => new Date(Date.parse(NOW) - m * 60_000).toISOString()

export const user = (over: Partial<User> = {}): User => ({
  id: 1,
  name: 'Alice',
  email: 'alice@example.com',
  plan: 'PRO',
  role: 'USER',
  createdAt: '2025-06-01T00:00:00Z',
  emailVerified: true,
  ...over,
})

export const httpMonitor = (over: Partial<Monitor> = {}): Monitor => ({
  id: 1,
  type: 'HTTP',
  name: 'Payments API',
  url: 'https://api.example.com/health',
  method: 'GET',
  expectedStatus: 200,
  expectedStatuses: [200, 204],
  intervalSeconds: 60,
  timeoutMs: 5000,
  state: 'UP',
  isActive: true,
  lastCheckedAt: minutesAgo(1),
  createdAt: '2025-06-01T00:00:00Z',
  graceSeconds: null,
  pingUrl: null,
  headers: [],
  requestBody: null,
  ...over,
})

export const heartbeatMonitor = (over: Partial<Monitor> = {}): Monitor =>
  httpMonitor({
    id: 2,
    type: 'HEARTBEAT',
    name: 'Nightly backup',
    url: '',
    intervalSeconds: 86_400,
    graceSeconds: 3600,
    pingUrl: 'https://pulseguard.example/api/ping/AbCdEfGhIjKlMnOpQrStUvWx',
    ...over,
  })

export const withStats = (m: Monitor, over: Partial<MonitorWithStats> = {}): MonitorWithStats => ({
  ...m,
  uptime24h: 99.9,
  lastResponseTimeMs: 120,
  lastStatusCode: 200,
  recentChecks: [true, true, true],
  ...over,
})

export const stats = (over: Partial<MonitorRangeStats> = {}): MonitorRangeStats => ({
  range: '24h',
  uptimePct: 99.5,
  avgResponseMs: 140,
  p95ResponseMs: 210,
  checksCount: 1440,
  incidentCount: 1,
  downtimeSeconds: 300,
  responseSeries: [],
  uptimeBuckets: [],
  previous: null,
  ...over,
})

export const check = (over: Partial<Check> = {}): Check => ({
  id: 1,
  monitorId: 1,
  result: 'UP',
  statusCode: 200,
  responseTimeMs: 120,
  errorType: null,
  errorMessage: null,
  checkedAt: minutesAgo(1),
  ...over,
})

export const incident = (over: Partial<Incident> = {}): Incident => ({
  id: 10,
  monitorId: 1,
  monitorName: 'Payments API',
  status: 'RESOLVED',
  cause: 'STATUS_MISMATCH: Expected 200 but got 503',
  startedAt: minutesAgo(90),
  resolvedAt: minutesAgo(60),
  ...over,
})

export const incidentDetail = (over: Partial<IncidentDetail> = {}): IncidentDetail => ({
  ...incident(),
  monitorType: 'HTTP',
  monitorUrl: 'https://api.example.com/health',
  monitorMethod: 'GET',
  intervalSeconds: 60,
  timeline: [
    { type: 'CHECK_FAILED', at: minutesAgo(90), detail: 'STATUS_MISMATCH: Expected 200 but got 503' },
    { type: 'OPENED', at: minutesAgo(88), failedChecks: 3 },
    { type: 'NOTIFIED', at: minutesAgo(88), event: 'OPENED', channel: 'EMAIL', target: 'alice@example.com', status: 'SENT' },
    { type: 'CHECK_PASSED', at: minutesAgo(61) },
    { type: 'RESOLVED', at: minutesAgo(60), passedChecks: 2 },
  ],
  ...over,
})

export const billing = (over: Partial<BillingSummary> = {}): BillingSummary => ({
  plan: 'FREE',
  status: null,
  currentPeriodEnd: null,
  cancelAtPeriodEnd: false,
  usage: { monitors: 2 },
  ...over,
})

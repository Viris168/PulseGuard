import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  AlertCircle,
  AlertTriangle,
  ArrowLeft,
  Clock,
  ExternalLink,
  HeartPulse,
  PauseCircle,
  Pause,
  Pencil,
  Lock,
  Play,
  RefreshCw,
  Trash2,
  XCircle,
} from 'lucide-react'
import { loadErrorMessage } from '../api/errors'
import { getMonitor, getMonitorStats, listChecks, listPings, pauseMonitor, resumeMonitor, sendTestPing } from '../api/monitors'
import { listIncidents } from '../api/incidents'
import { useAuth } from '../auth/authContext'
import { countDelta, pointsDelta, relativeDelta } from '../lib/delta'
import { limitsFor, nextPlan, planInfo } from '../lib/plans'
import type { Check, MonitorRangeStats, Ping, StatsRange } from '../types/check'
import type { Incident } from '../types/incident'
import { displayStatus, type Monitor } from '../types/monitor'
import {
  cn,
  formatCount,
  formatDateTime,
  formatDuration,
  formatInterval,
  formatMs,
  formatSpan,
  formatUptime,
  heartbeatDue,
  heartbeatSchedule,
  incidentDurationSeconds,
  timeAgo,
} from '../lib/format'
import { ResponseTimeChart } from '../components/charts/ResponseTimeChart'
import { UptimeBars } from '../components/charts/UptimeBars'
import { ChecksTable } from '../components/monitors/ChecksTable'
import { HeartbeatSetup } from '../components/monitors/HeartbeatSetup'
import { PingsTable } from '../components/monitors/PingsTable'
import { DeleteMonitorDialog } from '../components/monitors/DeleteMonitorDialog'
import { IncidentStatusPill } from '../components/incidents/IncidentStatusPill'
import { Button, ButtonLink } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { EmptyState } from '../components/ui/EmptyState'
import { Spinner } from '../components/ui/Spinner'
import { StatCard } from '../components/ui/StatCard'
import { StatusBadge } from '../components/ui/StatusBadge'

const RANGES: { value: StatsRange; label: string; long: string; bucket: string; days: number }[] = [
  { value: '24h', label: '24h', long: 'last 24 hours', bucket: '15-minute averages', days: 1 },
  { value: '7d', label: '7d', long: 'last 7 days', bucket: 'Hourly averages', days: 7 },
  { value: '30d', label: '30d', long: 'last 30 days', bucket: '4-hour averages', days: 30 },
]

const durationSeconds = incidentDurationSeconds

export function MonitorDetailPage() {
  const id = Number(useParams().id)
  const { user } = useAuth()
  const plan = user?.plan ?? 'FREE'
  const retentionDays = limitsFor(plan).retentionDays
  const navigate = useNavigate()

  const [monitor, setMonitor] = useState<Monitor | null>(null)
  const [checks, setChecks] = useState<Check[] | null>(null)
  const [pings, setPings] = useState<Ping[] | null>(null)
  const [incidents, setIncidents] = useState<Incident[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  const [range, setRange] = useState<StatsRange>('24h')
  // Stats are tagged with the range + version they were fetched for, so "loading" is derived:
  // while a new range loads, the previous render stays up (dimmed) instead of flashing a skeleton.
  const [stats, setStats] = useState<{ key: string; data: MonitorRangeStats } | null>(null)
  const [version, setVersion] = useState(0)
  const statsKey = `${range}:${version}`
  const statsLoading = stats?.key !== statsKey

  const [toggling, setToggling] = useState(false)
  const [deleteOpen, setDeleteOpen] = useState(false)

  const loadMonitor = useCallback(
    () =>
      // Each list is empty for the other monitor type, so fetch both rather than wait on getMonitor.
      Promise.all([getMonitor(id), listChecks(id, { limit: 20 }), listPings(id, { limit: 20 }), listIncidents({ monitorId: id })])
        .then(([m, c, p, i]) => {
          setMonitor(m)
          setChecks(c)
          setPings(p)
          setIncidents(i)
        })
        .catch((e: unknown) => setLoadError(loadErrorMessage(e, 'monitor'))),
    [id],
  )

  useEffect(() => {
    loadMonitor()
  }, [loadMonitor])

  useEffect(() => {
    let cancelled = false
    getMonitorStats(id, range)
      .then((data) => !cancelled && setStats({ key: statsKey, data }))
      .catch(() => {
        // The monitor load reports errors (e.g. 404); a failed stats call just keeps the old frame.
      })
    return () => {
      cancelled = true
    }
  }, [id, range, statsKey])

  async function testPing() {
    setMonitor(await sendTestPing(id))
    await loadMonitor()
    setVersion((v) => v + 1)
  }

  async function togglePause() {
    if (!monitor) return
    setToggling(true)
    try {
      setMonitor(monitor.isActive ? await pauseMonitor(id) : await resumeMonitor(id))
      setVersion((v) => v + 1)
    } finally {
      setToggling(false)
    }
  }

  const back = (
    <Link
      to="/monitors"
      className="mb-4 inline-flex items-center gap-1.5 text-sm text-zinc-500 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-white"
    >
      <ArrowLeft className="size-4" aria-hidden />
      Back to monitors
    </Link>
  )

  if (loadError) {
    return (
      <>
        {back}
        <Card>
          <EmptyState
            icon={AlertCircle}
            title="Monitor not found"
            description={loadError}
            action={<ButtonLink to="/monitors" variant="secondary">Back to monitors</ButtonLink>}
          />
        </Card>
      </>
    )
  }

  if (!monitor) {
    return (
      <>
        {back}
        <div className="flex justify-center py-24 text-zinc-400">
          <Spinner />
        </div>
      </>
    )
  }

  const status = displayStatus(monitor)
  const openIncident = incidents?.find((i) => i.status === 'OPEN')
  const rangeInfo = RANGES.find((r) => r.value === range)!
  const s = stats?.data
  const hasData = !!s && s.responseSeries.some((p) => p.uptimePct !== null)
  const vsLabel = `vs previous ${range}`
  const heartbeat = monitor.type === 'HEARTBEAT'

  return (
    <>
      {back}

      {/* Header */}
      <div className="mb-6 flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="text-2xl font-semibold tracking-tight">{monitor.name}</h1>
            <StatusBadge status={status} />
          </div>
          {heartbeat ? (
            <>
              <p className="mt-1.5 inline-flex items-center gap-1.5 text-sm text-zinc-500 dark:text-zinc-400">
                <HeartPulse className="size-4" aria-hidden />
                Heartbeat monitor
              </p>
              <p className="mt-2 text-sm text-zinc-500 dark:text-zinc-400">
                Expects a ping {heartbeatSchedule(monitor)} · Last ping {timeAgo(monitor.lastCheckedAt)}
                {monitor.isActive && monitor.lastCheckedAt && <> · {heartbeatDue(monitor).text}</>}
              </p>
            </>
          ) : (
            <>
              <a
                href={monitor.url}
                target="_blank"
                rel="noopener noreferrer"
                className="mt-1.5 inline-flex max-w-full items-center gap-1.5 text-sm text-zinc-500 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-white"
              >
                <span className="font-mono text-xs font-medium text-zinc-400 dark:text-zinc-500">{monitor.method}</span>
                <span className="truncate">{monitor.url}</span>
                <ExternalLink className="size-3.5 shrink-0" aria-hidden />
              </a>
              <p className="mt-2 text-sm text-zinc-500 dark:text-zinc-400">
                Every {formatInterval(monitor.intervalSeconds)} · Timeout {formatDuration(monitor.timeoutMs / 1000)} · Expects{' '}
                {monitor.expectedStatus} · Last check {timeAgo(monitor.lastCheckedAt)}
              </p>
            </>
          )}
        </div>
        <div className="flex shrink-0 gap-2">
          <Button variant="secondary" onClick={togglePause} loading={toggling}>
            {!toggling && (monitor.isActive ? <Pause className="size-4" aria-hidden /> : <Play className="size-4" aria-hidden />)}
            {monitor.isActive ? 'Pause' : 'Resume'}
          </Button>
          <ButtonLink to={`/monitors/${id}/edit`} variant="secondary">
            <Pencil className="size-4" aria-hidden />
            Edit
          </ButtonLink>
          <Button variant="secondary" onClick={() => setDeleteOpen(true)} aria-label="Delete monitor" title="Delete monitor">
            <Trash2 className="size-4 text-red-600 dark:text-red-400" aria-hidden />
          </Button>
        </div>
      </div>

      <StateBanner monitor={monitor} openIncident={openIncident} />

      {heartbeat && (
        <div className="mb-6">
          <HeartbeatSetup monitor={monitor} onTestPing={testPing} />
        </div>
      )}

      {/* Range filter — scopes the stats, chart and uptime strip below it. */}
      <div className="mb-4 flex items-center gap-3">
        <div className="inline-flex rounded-lg border border-zinc-200 bg-white p-0.5 dark:border-zinc-800 dark:bg-zinc-900" role="radiogroup" aria-label="Time range">
          {RANGES.map((r) => {
            // Beyond the plan's retention the backend has no data to return (403).
            const locked = r.days > retentionDays
            return (
              <button
                key={r.value}
                role="radio"
                aria-checked={range === r.value}
                disabled={locked}
                title={locked ? `${planInfo(plan).name} keeps ${retentionDays} days of history` : undefined}
                onClick={() => setRange(r.value)}
                className={cn(
                  'inline-flex items-center gap-1 rounded-md px-3 py-1 text-sm font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50',
                  range === r.value
                    ? 'bg-zinc-900 text-white dark:bg-white dark:text-zinc-900'
                    : 'text-zinc-600 enabled:hover:text-zinc-900 dark:text-zinc-400 dark:enabled:hover:text-white',
                )}
              >
                {locked && <Lock className="size-3" aria-hidden />}
                {r.label}
              </button>
            )
          })}
        </div>
        <span className="text-sm text-zinc-500 dark:text-zinc-400">
          Showing the {rangeInfo.long}
          {RANGES.some((r) => r.days > retentionDays) && (
            <>
              {' · '}
              <Link to="/billing" className="font-medium text-emerald-700 hover:underline dark:text-emerald-400">
                Upgrade for {nextPlan(plan)?.limits.retentionDays}-day history
              </Link>
            </>
          )}
        </span>
        {statsLoading && s && <Spinner className="size-4 text-zinc-400" />}
      </div>

      <div className={cn('space-y-4 transition-opacity', statsLoading && s && 'opacity-50')}>
        <div className="grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-4">
          <StatCard
            label="Uptime"
            value={s ? formatUptime(s.uptimePct) : null}
            delta={s?.previous && pointsDelta(s.uptimePct, s.previous.uptimePct)}
            deltaLabel={vsLabel}
            sub={s && `${formatCount(s.checksCount)} ${heartbeat ? 'expected pings' : 'checks'}`}
            tone={s?.uptimePct != null && s.uptimePct < 99 ? 'bad' : undefined}
          />
          {heartbeat ? (
            <>
              <StatCard
                label="Last ping"
                value={s ? timeAgo(monitor.lastCheckedAt) : null}
                sub={monitor.lastCheckedAt ? formatDateTime(monitor.lastCheckedAt) : 'None yet'}
              />
              <StatCard
                label="Expected"
                value={s ? `Every ${formatDuration(monitor.intervalSeconds)}` : null}
                sub={monitor.graceSeconds ? `+${formatDuration(monitor.graceSeconds)} grace` : undefined}
              />
            </>
          ) : (
            <>
              <StatCard
                label="Avg. response"
                value={s ? formatMs(s.avgResponseMs) : null}
                delta={s?.previous && relativeDelta(s.avgResponseMs, s.previous.avgResponseMs)}
                deltaLabel={vsLabel}
              />
              <StatCard
                label="P95 response"
                value={s ? formatMs(s.p95ResponseMs) : null}
                delta={s?.previous && relativeDelta(s.p95ResponseMs, s.previous.p95ResponseMs)}
                deltaLabel={vsLabel}
                sub="95% of checks were faster"
              />
            </>
          )}
          <StatCard
            label="Incidents"
            value={s ? s.incidentCount : null}
            delta={s?.previous && countDelta(s.incidentCount, s.previous.incidentCount)}
            deltaLabel={vsLabel}
            sub={s && (s.downtimeSeconds ? `${formatDuration(s.downtimeSeconds)} total downtime` : 'No downtime')}
            tone={s?.incidentCount ? 'bad' : undefined}
          />
        </div>

        {!heartbeat && <ResponseCard stats={s} hasData={hasData} bucketLabel={rangeInfo.bucket} range={range} />}

        <Card className="p-4 sm:p-5">
          <SectionTitle title="Uptime" subtitle={s ? `${formatUptime(s.uptimePct)} over the ${rangeInfo.long}` : undefined} />
          <div className="mt-4">
            {s ? <UptimeBars buckets={s.uptimeBuckets} range={range} /> : <div className="h-8 animate-pulse rounded bg-zinc-100 dark:bg-zinc-800" />}
          </div>
        </Card>
      </div>

      <div className="mt-6 grid gap-4 lg:grid-cols-3">
        <Card className="overflow-hidden lg:col-span-2">
          <div className="p-4 pb-3 sm:p-5 sm:pb-3">
            {heartbeat ? (
              <SectionTitle title="Recent pings" subtitle={pings ? `Newest ${pings.length}` : undefined} />
            ) : (
              <SectionTitle title="Latest checks" subtitle={checks ? `Newest ${checks.length}` : undefined} />
            )}
          </div>
          {heartbeat ? <PingsTable pings={pings} /> : <ChecksTable checks={checks} />}
        </Card>

        <Card className="overflow-hidden">
          <div className="p-4 pb-3 sm:p-5 sm:pb-3">
            <SectionTitle title="Incidents" subtitle={incidents ? `${incidents.length} total` : undefined}>
              {!!incidents?.length && (
                <Link to={`/incidents?monitor=${id}`} className="text-sm font-medium text-emerald-700 hover:underline dark:text-emerald-400">
                  View all
                </Link>
              )}
            </SectionTitle>
          </div>
          <IncidentList incidents={incidents} />
        </Card>
      </div>

      <DeleteMonitorDialog
        monitor={deleteOpen ? monitor : null}
        onClose={() => setDeleteOpen(false)}
        onDeleted={() => navigate('/monitors')}
      />
    </>
  )
}

function SectionTitle({ title, subtitle, children }: { title: string; subtitle?: string; children?: ReactNode }) {
  return (
    <div className="flex items-start justify-between gap-3">
      <div>
        <h2 className="text-base font-semibold">{title}</h2>
        {subtitle && <p className="mt-0.5 text-xs text-zinc-500 dark:text-zinc-400">{subtitle}</p>}
      </div>
      {children}
    </div>
  )
}

function StateBanner({ monitor, openIncident }: { monitor: Monitor; openIncident?: Incident }) {
  let tone: 'red' | 'amber' | 'sky' | 'zinc' | 'violet'
  let icon: ReactNode
  let title: string
  let body: ReactNode

  if (!monitor.isActive) {
    tone = 'zinc'
    icon = <PauseCircle className="size-5" />
    title = 'Monitoring is paused'
    body = 'No checks run and no alerts are sent until you resume.'
  } else if (openIncident && monitor.state === 'RECOVERING') {
    tone = 'sky'
    icon = <RefreshCw className="size-5" />
    title = 'Recovering'
    body = `Checks are passing again. The incident resolves after 2 passing checks in a row — down for ${formatDuration(durationSeconds(openIncident))} so far.`
  } else if (monitor.lastCheckedAt === null) {
    tone = 'violet'
    icon = <Clock className="size-5" />
    title = monitor.type === 'HEARTBEAT' ? 'Waiting for the first ping' : 'Waiting for the first check'
    body =
      monitor.type === 'HEARTBEAT'
        ? 'Add the ping URL below to your job, or send a test ping. Monitoring starts with the first one.'
        : 'The first check runs within a minute.'
  } else if (openIncident) {
    tone = 'red'
    icon = <XCircle className="size-5" />
    title = `Down for ${formatDuration(durationSeconds(openIncident))}`
    body =
      monitor.type === 'HEARTBEAT'
        ? `${(openIncident.cause ?? 'No ping received').replace(/\.?$/, '.')} The next ping resolves this.`
        : (openIncident.cause ?? 'Checks are failing.')
  } else if (monitor.state === 'SUSPICIOUS') {
    tone = 'amber'
    icon = <AlertTriangle className="size-5" />
    title = 'Checks are failing'
    body = 'An incident opens after 3 failed checks in a row. A passing check clears this.'
  } else {
    return null
  }

  const tones = {
    red: 'border-red-200 bg-red-50 text-red-900 dark:border-red-500/30 dark:bg-red-500/10 dark:text-red-200',
    amber: 'border-amber-200 bg-amber-50 text-amber-900 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-200',
    sky: 'border-sky-200 bg-sky-50 text-sky-900 dark:border-sky-500/30 dark:bg-sky-500/10 dark:text-sky-200',
    zinc: 'border-zinc-200 bg-zinc-100 text-zinc-800 dark:border-zinc-700 dark:bg-zinc-800/60 dark:text-zinc-200',
    violet: 'border-violet-200 bg-violet-50 text-violet-900 dark:border-violet-500/30 dark:bg-violet-500/10 dark:text-violet-200',
  }

  return (
    <div className={cn('mb-6 flex gap-3 rounded-xl border p-4', tones[tone])} role="status">
      <span className="mt-0.5 shrink-0" aria-hidden>
        {icon}
      </span>
      <div className="min-w-0 flex-1">
        <p className="font-semibold">{title}</p>
        <p className="mt-0.5 text-sm opacity-90">{body}</p>
      </div>
      {openIncident && (
        <Link to={`/incidents/${openIncident.id}`} className="shrink-0 self-center text-sm font-medium underline-offset-2 hover:underline">
          View incident
        </Link>
      )}
    </div>
  )
}

interface ResponseCardProps {
  stats: MonitorRangeStats | undefined
  hasData: boolean
  bucketLabel: string
  range: StatsRange
}

function ResponseCard({ stats, hasData, bucketLabel, range }: ResponseCardProps) {
  const [view, setView] = useState<'chart' | 'table'>('chart')

  return (
    <Card className="p-4 sm:p-5">
      <SectionTitle title="Response time" subtitle={bucketLabel}>
        <div className="flex rounded-md border border-zinc-200 text-xs dark:border-zinc-700" role="radiogroup" aria-label="View as">
          {(['chart', 'table'] as const).map((v) => (
            <button
              key={v}
              role="radio"
              aria-checked={view === v}
              onClick={() => setView(v)}
              className={cn(
                'px-2.5 py-1 font-medium capitalize first:rounded-l-md last:rounded-r-md',
                view === v
                  ? 'bg-zinc-100 text-zinc-900 dark:bg-zinc-700 dark:text-white'
                  : 'text-zinc-500 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-white',
              )}
            >
              {v}
            </button>
          ))}
        </div>
      </SectionTitle>

      <div className="mt-4">
        {!stats ? (
          <div className="h-[232px] animate-pulse rounded-lg bg-zinc-100 dark:bg-zinc-800" />
        ) : !hasData ? (
          <p className="flex h-[232px] items-center justify-center text-sm text-zinc-500 dark:text-zinc-400">
            No checks in this range yet.
          </p>
        ) : view === 'chart' ? (
          <>
            <ResponseTimeChart points={stats.responseSeries} range={range} />
            <p className="mt-1 flex items-center gap-1.5 text-xs text-zinc-500 dark:text-zinc-400">
              <span className="h-3 w-2.5 rounded-[2px] bg-[var(--status-down)] opacity-30" aria-hidden />
              Shaded periods had downtime
            </p>
          </>
        ) : (
          <div className="max-h-[260px] overflow-auto rounded-lg border border-zinc-200 dark:border-zinc-800">
            <table className="w-full text-left text-sm">
              <thead className="sticky top-0 bg-zinc-50 text-xs text-zinc-500 dark:bg-zinc-800 dark:text-zinc-400">
                <tr>
                  <th className="px-3 py-2 font-medium">Period</th>
                  <th className="px-3 py-2 text-right font-medium">Avg. response</th>
                  <th className="px-3 py-2 text-right font-medium">Uptime</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-zinc-100 tabular-nums dark:divide-zinc-800">
                {[...stats.responseSeries].reverse().map((p) => (
                  <tr key={p.start}>
                    <td className="px-3 py-1.5 whitespace-nowrap text-zinc-600 dark:text-zinc-400">{formatSpan(p.start, p.end)}</td>
                    <td className="px-3 py-1.5 text-right">{formatMs(p.avgResponseMs)}</td>
                    <td className={cn('px-3 py-1.5 text-right', p.uptimePct !== null && p.uptimePct < 100 && 'text-red-600 dark:text-red-400')}>
                      {formatUptime(p.uptimePct)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </Card>
  )
}

function IncidentList({ incidents }: { incidents: Incident[] | null }) {
  if (!incidents) {
    return (
      <div className="flex justify-center py-12 text-zinc-400">
        <Spinner />
      </div>
    )
  }
  if (!incidents.length) {
    return <p className="px-5 pb-8 pt-4 text-center text-sm text-zinc-500 dark:text-zinc-400">No incidents. Smooth sailing so far.</p>
  }
  return (
    <ul className="max-h-[420px] divide-y divide-zinc-100 overflow-auto border-t border-zinc-200 dark:divide-zinc-800 dark:border-zinc-800">
      {incidents.map((i) => (
        <li key={i.id}>
          <Link to={`/incidents/${i.id}`} className="block px-4 py-3 hover:bg-zinc-50 sm:px-5 dark:hover:bg-zinc-800/40">
            <div className="flex items-center justify-between gap-2">
              <IncidentStatusPill status={i.status} />
              <span className="text-xs text-zinc-500 tabular-nums dark:text-zinc-400">
                {formatDuration(durationSeconds(i))}
                {i.status === 'OPEN' && ' so far'}
              </span>
            </div>
            <p className="mt-1 truncate text-sm font-medium" title={i.cause ?? undefined}>
              {i.cause ?? 'Unknown cause'}
            </p>
            <p className="mt-0.5 text-xs text-zinc-500 dark:text-zinc-400">{formatDateTime(i.startedAt)}</p>
          </Link>
        </li>
      ))}
    </ul>
  )
}

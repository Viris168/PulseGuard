import { useCallback, useEffect, useMemo, useState, type ButtonHTMLAttributes, type ReactNode } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Activity, AlertCircle, HeartPulse, Pause, Pencil, Play, Plus, Search, Trash2 } from 'lucide-react'
import { listMonitors, pauseMonitor, resumeMonitor } from '../api/monitors'
import { displayStatus, type DisplayStatus, type MonitorWithStats } from '../types/monitor'
import { cn, formatInterval, formatMs, formatUptime, heartbeatDue, heartbeatSchedule, timeAgo } from '../lib/format'
import { useAuth } from '../auth/authContext'
import { formatMonitorLimit, limitsFor, nextPlan, planInfo } from '../lib/plans'
import { UpgradePrompt } from '../components/billing/UpgradePrompt'
import { PageHeader } from '../components/layout/PageHeader'
import { Button, ButtonLink } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { CheckBars } from '../components/ui/CheckBars'
import { EmptyState } from '../components/ui/EmptyState'
import { StatCard } from '../components/ui/StatCard'
import { DeleteMonitorDialog } from '../components/monitors/DeleteMonitorDialog'
import { StatusBadge } from '../components/ui/StatusBadge'

type Filter = 'ALL' | 'UP' | 'ISSUES' | 'PAUSED'

const filterMatch: Record<Filter, (s: DisplayStatus) => boolean> = {
  ALL: () => true,
  UP: (s) => s === 'UP' || s === 'RECOVERING',
  ISSUES: (s) => s === 'DOWN' || s === 'SUSPICIOUS',
  PAUSED: (s) => s === 'PAUSED',
}

// Worst first, so problems are at the top of the list.
const severity: Record<DisplayStatus, number> = { DOWN: 0, SUSPICIOUS: 1, RECOVERING: 2, PENDING: 3, UP: 4, PAUSED: 5 }

export function MonitorsPage() {
  const { user } = useAuth()
  const [monitors, setMonitors] = useState<MonitorWithStats[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState<Filter>('ALL')
  const [busyId, setBusyId] = useState<number | null>(null)
  const [toDelete, setToDelete] = useState<MonitorWithStats | null>(null)

  const fetchMonitors = useCallback(
    () =>
      listMonitors()
        .then(setMonitors)
        .catch((e: unknown) => setError(e instanceof Error ? e.message : 'Failed to load monitors')),
    [],
  )

  useEffect(() => {
    fetchMonitors()
  }, [fetchMonitors])

  function retry() {
    setError(null)
    fetchMonitors()
  }

  const counts = useMemo(() => {
    const list = monitors ?? []
    const statuses = list.map(displayStatus)
    const withUptime = list.filter((m) => m.isActive && m.uptime24h !== null)
    return {
      ALL: list.length,
      UP: statuses.filter(filterMatch.UP).length,
      ISSUES: statuses.filter(filterMatch.ISSUES).length,
      PAUSED: statuses.filter(filterMatch.PAUSED).length,
      avgUptime: withUptime.length ? withUptime.reduce((sum, m) => sum + m.uptime24h!, 0) / withUptime.length : null,
    }
  }, [monitors])

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase()
    return (monitors ?? [])
      .filter((m) => filterMatch[filter](displayStatus(m)))
      .filter((m) => !q || m.name.toLowerCase().includes(q) || m.url.toLowerCase().includes(q))
      .sort((a, b) => severity[displayStatus(a)] - severity[displayStatus(b)] || a.name.localeCompare(b.name))
  }, [monitors, query, filter])

  async function togglePause(m: MonitorWithStats) {
    setBusyId(m.id)
    try {
      const updated = m.isActive ? await pauseMonitor(m.id) : await resumeMonitor(m.id)
      setMonitors((list) => list?.map((x) => (x.id === m.id ? { ...x, ...updated } : x)) ?? null)
    } finally {
      setBusyId(null)
    }
  }

  // The backend enforces this too; the UI just says so before the user fills in a form.
  const maxMonitors = user ? limitsFor(user.plan).maxMonitors : Infinity
  const atLimit = !!monitors && monitors.length >= maxMonitors
  const upgradeTo = user && nextPlan(user.plan)

  const addButton = atLimit ? (
    <Button disabled title={`Your plan allows ${maxMonitors} monitors`}>
      <Plus className="size-4" aria-hidden />
      Add monitor
    </Button>
  ) : (
    <ButtonLink to="/monitors/new">
      <Plus className="size-4" aria-hidden />
      Add monitor
    </ButtonLink>
  )

  return (
    <>
      <PageHeader
        title="Monitors"
        description={
          monitors && maxMonitors !== Infinity
            ? `Uptime and response of every endpoint you watch · ${monitors.length} of ${maxMonitors} used`
            : 'Uptime and response of every endpoint you watch.'
        }
        actions={addButton}
      />

      {atLimit && user && (
        <UpgradePrompt
          className="mb-6"
          title={`You're using all ${maxMonitors} monitors on the ${planInfo(user.plan).name} plan`}
          cta={upgradeTo ? `Upgrade to ${upgradeTo.name}` : 'See plans'}
        >
          {upgradeTo &&
            `${upgradeTo.name} gives you ${formatMonitorLimit(upgradeTo.limits.maxMonitors).toLowerCase()} monitors and checks every minute.`}
        </UpgradePrompt>
      )}

      <div className="grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-4">
        <StatCard label="Total monitors" value={monitors ? counts.ALL : null} />
        <StatCard label="Operational" value={monitors ? counts.UP : null} tone={counts.UP ? 'good' : undefined} />
        <StatCard label="Need attention" value={monitors ? counts.ISSUES : null} tone={counts.ISSUES ? 'bad' : undefined} />
        <StatCard label="Avg. uptime (24h)" value={monitors ? formatUptime(counts.avgUptime) : null} />
      </div>

      <Card className="mt-6 overflow-hidden">
        <div className="flex flex-col gap-3 border-b border-zinc-200 p-4 sm:flex-row sm:items-center sm:justify-between dark:border-zinc-800">
          <div className="flex gap-1 overflow-x-auto" role="tablist" aria-label="Filter monitors">
            {(
              [
                ['ALL', 'All'],
                ['UP', 'Up'],
                ['ISSUES', 'Issues'],
                ['PAUSED', 'Paused'],
              ] as const
            ).map(([key, label]) => (
              <button
                key={key}
                role="tab"
                aria-selected={filter === key}
                onClick={() => setFilter(key)}
                className={cn(
                  'rounded-md px-3 py-1.5 text-sm font-medium whitespace-nowrap transition-colors',
                  filter === key
                    ? 'bg-zinc-900 text-white dark:bg-white dark:text-zinc-900'
                    : 'text-zinc-600 hover:bg-zinc-100 dark:text-zinc-400 dark:hover:bg-zinc-800',
                )}
              >
                {label}
                {monitors && <span className="ml-1.5 opacity-60">{counts[key]}</span>}
              </button>
            ))}
          </div>
          <div className="relative sm:w-64">
            <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-zinc-400" aria-hidden />
            <input
              type="search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search name or URL"
              aria-label="Search monitors"
              className="w-full rounded-lg border border-zinc-300 bg-white py-1.5 pr-3 pl-9 text-sm placeholder:text-zinc-400 focus:outline-2 focus:-outline-offset-1 focus:outline-emerald-600 dark:border-zinc-700 dark:bg-zinc-950"
            />
          </div>
        </div>

        {error ? (
          <EmptyState
            icon={AlertCircle}
            title="Couldn't load monitors"
            description={error}
            action={<Button variant="secondary" onClick={retry}>Try again</Button>}
          />
        ) : !monitors ? (
          <LoadingRows />
        ) : monitors.length === 0 ? (
          <EmptyState
            icon={Activity}
            title="No monitors yet"
            description="Add your first URL and PulseGuard will start checking it right away."
            action={addButton}
          />
        ) : visible.length === 0 ? (
          <EmptyState icon={Search} title="No matches" description="Try a different search or filter." />
        ) : (
          // Rows size to this list, not the window, so they still fit when Ask AI is docked beside them.
          <ul className="@container divide-y divide-zinc-200 dark:divide-zinc-800">
            {visible.map((m) => (
              <MonitorRow
                key={m.id}
                monitor={m}
                busy={busyId === m.id}
                onTogglePause={() => togglePause(m)}
                onDelete={() => setToDelete(m)}
              />
            ))}
          </ul>
        )}
      </Card>

      <DeleteMonitorDialog
        monitor={toDelete}
        onClose={() => setToDelete(null)}
        onDeleted={(id) => {
          setMonitors((list) => list?.filter((x) => x.id !== id) ?? null)
          setToDelete(null)
        }}
      />
    </>
  )
}

interface RowProps {
  monitor: MonitorWithStats
  busy: boolean
  onTogglePause: () => void
  onDelete: () => void
}

function MonitorRow({ monitor: m, busy, onTogglePause, onDelete }: RowProps) {
  const navigate = useNavigate()
  const status = displayStatus(m)
  const heartbeat = m.type === 'HEARTBEAT'
  const slow = !heartbeat && m.lastResponseTimeMs !== null && m.lastResponseTimeMs > m.timeoutMs * 0.8
  const due = heartbeatDue(m)

  return (
    <li
      className="group relative flex flex-col gap-3 px-4 py-4 transition-colors hover:bg-zinc-50 @2xl:flex-row @2xl:items-center @2xl:gap-6 dark:hover:bg-zinc-800/40"
    >
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2.5">
          <StatusBadge status={status} />
          <Link
            to={`/monitors/${m.id}`}
            className="truncate font-medium hover:underline after:absolute after:inset-0 after:content-['']"
          >
            {m.name}
          </Link>
        </div>
        <p className="mt-1 flex items-center truncate text-sm text-zinc-500 dark:text-zinc-400">
          {heartbeat ? (
            <>
              <HeartPulse className="mr-1.5 size-3.5 shrink-0 text-zinc-400 dark:text-zinc-500" aria-hidden />
              Heartbeat · {heartbeatSchedule(m)}
            </>
          ) : (
            <>
              <span className="mr-1.5 font-mono text-xs font-medium text-zinc-400 dark:text-zinc-500">{m.method}</span>
              <span className="truncate">{m.url}</span>
            </>
          )}
        </p>
      </div>

      <div className="hidden @5xl:block">
        <CheckBars checks={m.recentChecks} />
      </div>

      <dl className="grid grid-cols-3 gap-4 text-sm @2xl:flex @2xl:gap-6">
        <Metric label="Uptime 24h" value={formatUptime(m.uptime24h)} />
        {heartbeat ? (
          <>
            <Metric label="Last ping" value={timeAgo(m.lastCheckedAt)} />
            <Metric
              label="Next ping"
              value={m.isActive ? due.text.replace(/ \(in grace\)$/, '') : '—'}
              className={cn(
                m.isActive && due.tone === 'late' && 'text-amber-600 dark:text-amber-400',
                m.isActive && due.tone === 'overdue' && 'text-red-600 dark:text-red-400',
              )}
            />
          </>
        ) : (
          <>
            <Metric
              label="Response"
              value={formatMs(m.lastResponseTimeMs)}
              className={cn(slow && 'text-amber-600 dark:text-amber-400')}
            />
            <Metric label={`Every ${formatInterval(m.intervalSeconds)}`} value={timeAgo(m.lastCheckedAt)} />
          </>
        )}
      </dl>

      {/* z-10 keeps the actions clickable above the row-wide link overlay. */}
      <div className="relative z-10 flex gap-1 @2xl:opacity-60 @2xl:group-focus-within:opacity-100 @2xl:group-hover:opacity-100">
        <IconButton label={m.isActive ? 'Pause' : 'Resume'} onClick={onTogglePause} disabled={busy}>
          {m.isActive ? <Pause className="size-4" /> : <Play className="size-4" />}
        </IconButton>
        <IconButton label="Edit" onClick={() => navigate(`/monitors/${m.id}/edit`)}>
          <Pencil className="size-4" />
        </IconButton>
        <IconButton label="Delete" onClick={onDelete} danger>
          <Trash2 className="size-4" />
        </IconButton>
      </div>
    </li>
  )
}

function Metric({ label, value, className }: { label: string; value: string; className?: string }) {
  return (
    <div className="@2xl:w-24 @2xl:text-right">
      <dt className="text-xs text-zinc-500 dark:text-zinc-400">{label}</dt>
      <dd className={cn('font-medium tabular-nums', className)}>{value}</dd>
    </div>
  )
}

function IconButton({
  label,
  danger,
  children,
  ...rest
}: { label: string; danger?: boolean; children: ReactNode } & ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      className={cn(
        'rounded-md p-2 text-zinc-500 transition-colors disabled:opacity-40 dark:text-zinc-400',
        danger
          ? 'hover:bg-red-50 hover:text-red-600 dark:hover:bg-red-500/10 dark:hover:text-red-400'
          : 'hover:bg-zinc-200/70 hover:text-zinc-900 dark:hover:bg-zinc-700 dark:hover:text-white',
      )}
      {...rest}
    >
      {children}
    </button>
  )
}

function LoadingRows() {
  return (
    <ul className="divide-y divide-zinc-200 dark:divide-zinc-800" aria-busy>
      {Array.from({ length: 4 }, (_, i) => (
        <li key={i} className="flex items-center gap-4 px-4 py-5">
          <div className="h-5 w-16 animate-pulse rounded-full bg-zinc-100 dark:bg-zinc-800" />
          <div className="flex-1 space-y-2">
            <div className="h-4 w-40 animate-pulse rounded bg-zinc-100 dark:bg-zinc-800" />
            <div className="h-3 w-64 max-w-full animate-pulse rounded bg-zinc-100 dark:bg-zinc-800" />
          </div>
        </li>
      ))}
    </ul>
  )
}

import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { AlertCircle, Search, ShieldCheck } from 'lucide-react'
import { listIncidents } from '../api/incidents'
import type { Incident } from '../types/incident'
import { cn, formatDateTime, formatDuration, incidentDurationSeconds, timeAgo } from '../lib/format'
import { PageHeader } from '../components/layout/PageHeader'
import { IncidentStatusPill } from '../components/incidents/IncidentStatusPill'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { EmptyState } from '../components/ui/EmptyState'
import { StatCard } from '../components/ui/StatCard'

type StatusFilter = 'all' | 'open' | 'resolved'

const STATUS_TABS: { key: StatusFilter; label: string; match: (i: Incident) => boolean }[] = [
  { key: 'all', label: 'All', match: () => true },
  { key: 'open', label: 'Ongoing', match: (i) => i.status === 'OPEN' },
  { key: 'resolved', label: 'Resolved', match: (i) => i.status === 'RESOLVED' },
]

const THIRTY_DAYS_MS = 30 * 86_400_000

export function IncidentsPage() {
  const [incidents, setIncidents] = useState<Incident[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  // "Last 30 days" is measured from when the data arrived, keeping render pure.
  const [fetchedAt, setFetchedAt] = useState(0)

  // Filters live in the URL so a filtered view can be linked to (e.g. from a monitor page).
  const [params, setParams] = useSearchParams()
  const status = (STATUS_TABS.some((t) => t.key === params.get('status')) ? params.get('status') : 'all') as StatusFilter
  const monitorId = params.get('monitor') ?? ''
  const query = params.get('q') ?? ''

  function setParam(key: string, value: string, empty = '') {
    setParams(
      (p) => {
        if (value === empty) p.delete(key)
        else p.set(key, value)
        return p
      },
      { replace: true },
    )
  }

  const fetchIncidents = useCallback(
    () =>
      listIncidents()
        .then((list) => {
          setIncidents(list)
          setFetchedAt(Date.now())
        })
        .catch((e: unknown) => setError(e instanceof Error ? e.message : 'Failed to load incidents')),
    [],
  )

  useEffect(() => {
    fetchIncidents()
  }, [fetchIncidents])

  const monitors = useMemo(() => {
    const seen = new Map<number, string>()
    incidents?.forEach((i) => seen.set(i.monitorId, i.monitorName))
    return [...seen].sort((a, b) => a[1].localeCompare(b[1]))
  }, [incidents])

  const summary = useMemo(() => {
    if (!incidents) return null
    const since = fetchedAt - THIRTY_DAYS_MS
    const recent = incidents.filter((i) => !i.resolvedAt || Date.parse(i.resolvedAt) >= since)
    const resolved = recent.filter((i) => i.resolvedAt)
    const downtime = recent.reduce(
      (sum, i) => sum + incidentDurationSeconds({ ...i, startedAt: new Date(Math.max(since, Date.parse(i.startedAt))).toISOString() }),
      0,
    )
    return {
      ongoing: incidents.filter((i) => i.status === 'OPEN').length,
      count30d: recent.length,
      mttr: resolved.length ? resolved.reduce((s, i) => s + incidentDurationSeconds(i), 0) / resolved.length : null,
      downtime,
    }
  }, [incidents, fetchedAt])

  // Base set for tab counts: everything except the status filter itself.
  const scoped = useMemo(() => {
    const q = query.trim().toLowerCase()
    return (incidents ?? [])
      .filter((i) => !monitorId || String(i.monitorId) === monitorId)
      .filter((i) => !q || (i.cause ?? '').toLowerCase().includes(q) || i.monitorName.toLowerCase().includes(q))
  }, [incidents, monitorId, query])

  const visible = useMemo(
    () =>
      scoped
        .filter(STATUS_TABS.find((t) => t.key === status)!.match)
        // Ongoing first, then newest.
        .sort((a, b) => Number(b.status === 'OPEN') - Number(a.status === 'OPEN') || Date.parse(b.startedAt) - Date.parse(a.startedAt)),
    [scoped, status],
  )

  const filtered = !!monitorId || !!query || status !== 'all'

  return (
    <>
      <PageHeader title="Incidents" description="Every outage PulseGuard detected, from first failed check to recovery." />

      <div className="grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-4">
        <StatCard
          label="Ongoing now"
          value={summary ? summary.ongoing : null}
          sub={summary && (summary.ongoing ? 'need attention' : 'all clear')}
          tone={summary?.ongoing ? 'bad' : 'good'}
        />
        <StatCard label="Incidents" value={summary ? summary.count30d : null} sub="last 30 days" />
        <StatCard
          label="Mean time to resolve"
          value={summary ? (summary.mttr === null ? '—' : formatDuration(summary.mttr)) : null}
          sub="last 30 days"
        />
        <StatCard
          label="Total downtime"
          value={summary ? (summary.downtime ? formatDuration(summary.downtime) : 'None') : null}
          sub="last 30 days, all monitors"
        />
      </div>

      <Card className="mt-6 overflow-hidden">
        <div className="flex flex-col gap-3 border-b border-zinc-200 p-4 xl:flex-row xl:items-center xl:justify-between dark:border-zinc-800">
          <div className="flex shrink-0 gap-1 overflow-x-auto" role="tablist" aria-label="Filter by status">
            {STATUS_TABS.map((t) => (
              <button
                key={t.key}
                role="tab"
                aria-selected={status === t.key}
                onClick={() => setParam('status', t.key, 'all')}
                className={cn(
                  'rounded-md px-3 py-1.5 text-sm font-medium whitespace-nowrap transition-colors',
                  status === t.key
                    ? 'bg-zinc-900 text-white dark:bg-white dark:text-zinc-900'
                    : 'text-zinc-600 hover:bg-zinc-100 dark:text-zinc-400 dark:hover:bg-zinc-800',
                )}
              >
                {t.label}
                {incidents && <span className="ml-1.5 opacity-60">{scoped.filter(t.match).length}</span>}
              </button>
            ))}
          </div>
          <div className="flex flex-col gap-2 sm:flex-row">
            <select
              value={monitorId}
              onChange={(e) => setParam('monitor', e.target.value)}
              aria-label="Filter by monitor"
              className="rounded-lg border border-zinc-300 bg-white py-1.5 pr-8 pl-3 text-sm focus:outline-2 focus:-outline-offset-1 focus:outline-emerald-600 dark:border-zinc-700 dark:bg-zinc-950"
            >
              <option value="">All monitors</option>
              {monitors.map(([id, name]) => (
                <option key={id} value={id}>
                  {name}
                </option>
              ))}
            </select>
            <div className="relative sm:w-60">
              <Search className="pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2 text-zinc-400" aria-hidden />
              <input
                type="search"
                value={query}
                onChange={(e) => setParam('q', e.target.value)}
                placeholder="Search cause or monitor"
                aria-label="Search incidents"
                className="w-full rounded-lg border border-zinc-300 bg-white py-1.5 pr-3 pl-9 text-sm placeholder:text-zinc-400 focus:outline-2 focus:-outline-offset-1 focus:outline-emerald-600 dark:border-zinc-700 dark:bg-zinc-950"
              />
            </div>
          </div>
        </div>

        {error ? (
          <EmptyState
            icon={AlertCircle}
            title="Couldn't load incidents"
            description={error}
            action={
              <Button
                variant="secondary"
                onClick={() => {
                  setError(null)
                  fetchIncidents()
                }}
              >
                Try again
              </Button>
            }
          />
        ) : !incidents ? (
          <LoadingRows />
        ) : incidents.length === 0 ? (
          <EmptyState icon={ShieldCheck} title="No incidents yet" description="Every monitor has stayed healthy. Nice." />
        ) : visible.length === 0 ? (
          <EmptyState
            icon={Search}
            title="No matching incidents"
            description="Try a different filter or search."
            action={
              filtered && (
                <Button variant="secondary" onClick={() => setParams({}, { replace: true })}>
                  Clear filters
                </Button>
              )
            }
          />
        ) : (
          <ul className="divide-y divide-zinc-200 dark:divide-zinc-800">
            {visible.map((i) => (
              <IncidentRow key={i.id} incident={i} />
            ))}
          </ul>
        )}
      </Card>
    </>
  )
}

function IncidentRow({ incident: i }: { incident: Incident }) {
  const open = i.status === 'OPEN'
  return (
    <li className="relative flex flex-col gap-2 px-4 py-4 transition-colors hover:bg-zinc-50 md:flex-row md:items-center md:gap-6 dark:hover:bg-zinc-800/40">
      <div className={cn('hidden w-1 self-stretch rounded-full md:block', open ? 'bg-red-500' : 'bg-zinc-200 dark:bg-zinc-700')} aria-hidden />
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-3">
          <IncidentStatusPill status={i.status} />
          <span className="text-xs text-zinc-400 dark:text-zinc-500">#{i.id}</span>
        </div>
        <Link
          to={`/incidents/${i.id}`}
          className="mt-1 block truncate font-medium after:absolute after:inset-0 after:content-[''] hover:underline"
          title={i.cause ?? undefined}
        >
          {i.cause ?? 'Unknown cause'}
        </Link>
        <p className="mt-0.5 truncate text-sm text-zinc-500 dark:text-zinc-400">{i.monitorName}</p>
      </div>
      <dl className="grid grid-cols-2 gap-4 text-sm md:flex md:gap-8">
        <div className="md:w-44">
          <dt className="text-xs text-zinc-500 dark:text-zinc-400">Started</dt>
          <dd className="font-medium whitespace-nowrap">{formatDateTime(i.startedAt)}</dd>
          <dd className="text-xs text-zinc-500 dark:text-zinc-400">{timeAgo(i.startedAt)}</dd>
        </div>
        <div className="md:w-28 md:self-start md:text-right">
          <dt className="text-xs text-zinc-500 dark:text-zinc-400">Duration</dt>
          <dd className={cn('font-medium tabular-nums', open && 'text-red-600 dark:text-red-400')}>
            {formatDuration(incidentDurationSeconds(i))}
            {open && <span className="text-xs font-normal"> so far</span>}
          </dd>
        </div>
      </dl>
    </li>
  )
}

function LoadingRows() {
  return (
    <ul className="divide-y divide-zinc-200 dark:divide-zinc-800" aria-busy>
      {Array.from({ length: 4 }, (_, i) => (
        <li key={i} className="space-y-2 px-4 py-5">
          <div className="h-3 w-16 animate-pulse rounded bg-zinc-100 dark:bg-zinc-800" />
          <div className="h-4 w-64 max-w-full animate-pulse rounded bg-zinc-100 dark:bg-zinc-800" />
          <div className="h-3 w-32 animate-pulse rounded bg-zinc-100 dark:bg-zinc-800" />
        </li>
      ))}
    </ul>
  )
}

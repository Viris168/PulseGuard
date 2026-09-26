import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { AlertTriangle, CheckCircle2, PauseCircle, RefreshCw, SearchX, XCircle, type LucideIcon } from 'lucide-react'
import { ApiError } from '../../api/errors'
import { getPublicStatusPage } from '../../api/statusPages'
import type { ComponentStatus, OverallStatus, PublicIncident, PublicStatusPage as Page } from '../../types/statusPage'
import { cn, formatDuration, formatTime, formatUptime, incidentDurationSeconds } from '../../lib/format'
import { UptimeBars, UptimeLegend } from '../../components/charts/UptimeBars'
import { Spinner } from '../../components/ui/Spinner'

const REFRESH_MS = 60_000
const INCIDENT_DAYS = 14
// 90 cells on a phone are ~2px wide — unreadable and untappable — so small screens get 30.
const MOBILE_DAYS = 30
const MOBILE_QUERY = '(max-width: 639px)'

function useIsSmallScreen() {
  const [small, setSmall] = useState(() => window.matchMedia(MOBILE_QUERY).matches)
  useEffect(() => {
    const mq = window.matchMedia(MOBILE_QUERY)
    const onChange = () => setSmall(mq.matches)
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [])
  return small
}

/** Mean of the days that have data (days are equal length, so this is the period's uptime). */
function meanUptime(days: { uptimePct: number | null }[]): number | null {
  const known = days.flatMap((d) => (d.uptimePct === null ? [] : [d.uptimePct]))
  return known.length ? known.reduce((a, b) => a + b, 0) / known.length : null
}

const OVERALL: Record<OverallStatus, { title: string; icon: LucideIcon; className: string }> = {
  OPERATIONAL: { title: 'All systems operational', icon: CheckCircle2, className: 'bg-emerald-600 text-white' },
  DEGRADED: { title: 'Some systems are recovering', icon: RefreshCw, className: 'bg-sky-600 text-white' },
  PARTIAL_OUTAGE: { title: 'Partial outage', icon: AlertTriangle, className: 'bg-amber-500 text-zinc-950' },
  MAJOR_OUTAGE: { title: 'Major outage', icon: XCircle, className: 'bg-red-600 text-white' },
}

const COMPONENT: Record<ComponentStatus, { label: string; icon: LucideIcon; className: string }> = {
  OPERATIONAL: { label: 'Operational', icon: CheckCircle2, className: 'text-emerald-700 dark:text-emerald-400' },
  RECOVERING: { label: 'Recovering', icon: RefreshCw, className: 'text-sky-700 dark:text-sky-400' },
  OUTAGE: { label: 'Outage', icon: XCircle, className: 'text-red-600 dark:text-red-400' },
  PAUSED: { label: 'Not monitored', icon: PauseCircle, className: 'text-zinc-500 dark:text-zinc-400' },
}

const dayFmt = new Intl.DateTimeFormat(undefined, { weekday: 'short', month: 'short', day: 'numeric' })

/** The last 14 calendar days, today first, each with the incidents that started that day. */
function groupByDay(incidents: PublicIncident[], now: number) {
  return Array.from({ length: INCIDENT_DAYS }, (_, i) => {
    const day = new Date(now - i * 86_400_000)
    const key = day.toDateString()
    return { label: i === 0 ? 'Today' : dayFmt.format(day), items: incidents.filter((x) => new Date(x.startedAt).toDateString() === key) }
  })
}

export function PublicStatusPage() {
  const { slug = '' } = useParams()
  const [page, setPage] = useState<Page | null>(null)
  const [notFound, setNotFound] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const small = useIsSmallScreen()

  const load = useCallback(
    () =>
      getPublicStatusPage(slug)
        .then((p) => {
          setPage(p)
          setError(null)
        })
        .catch((e: unknown) => {
          if (e instanceof ApiError && e.status === 404) setNotFound(true)
          // Keep showing the last good data if a refresh fails.
          else setError(e instanceof Error ? e.message : 'Could not load status')
        }),
    [slug],
  )

  useEffect(() => {
    load()
    const timer = setInterval(load, REFRESH_MS)
    return () => clearInterval(timer)
  }, [load])

  useEffect(() => {
    if (page) document.title = page.title
    return () => {
      document.title = 'PulseGuard'
    }
  }, [page])

  if (notFound) {
    return (
      <Shell>
        <div className="flex flex-col items-center py-24 text-center">
          <SearchX className="size-10 text-zinc-400" aria-hidden />
          <h1 className="mt-4 text-xl font-semibold">Status page not found</h1>
          <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">Check the address, or the page may not be published.</p>
        </div>
      </Shell>
    )
  }

  if (!page) {
    return (
      <Shell>
        <div className="flex justify-center py-32 text-zinc-400">{error ? <p className="text-sm">{error}</p> : <Spinner />}</div>
      </Shell>
    )
  }

  const overall = OVERALL[page.overall]
  const OverallIcon = overall.icon
  const affected = page.components.filter((c) => c.status === 'OUTAGE').length
  const days = groupByDay(page.incidents, Date.parse(page.generatedAt))
  const shownDays = small ? Math.min(MOBILE_DAYS, page.historyDays) : page.historyDays

  return (
    <Shell>
      <header>
        <h1 className="text-3xl font-semibold tracking-tight">{page.title}</h1>
        {page.description && <p className="mt-2 text-zinc-600 dark:text-zinc-400">{page.description}</p>}
      </header>

      <div className={cn('mt-8 flex items-center gap-3 rounded-xl px-5 py-4 shadow-sm', overall.className)} role="status">
        <OverallIcon className="size-6 shrink-0" aria-hidden />
        <div>
          <p className="text-lg font-semibold">{overall.title}</p>
          {affected > 0 && (
            <p className="text-sm opacity-90">
              {affected} of {page.components.length} {page.components.length === 1 ? 'system' : 'systems'} affected
            </p>
          )}
        </div>
      </div>

      <section className="mt-8 rounded-xl border border-zinc-200 bg-white shadow-sm dark:border-zinc-800 dark:bg-zinc-900" aria-label="Systems">
        <ul className="divide-y divide-zinc-200 dark:divide-zinc-800">
          {page.components.map((c) => {
            const s = COMPONENT[c.status]
            const visible = c.days.slice(-shownDays)
            const uptime = shownDays === page.historyDays ? c.uptimePct : meanUptime(visible)
            const Icon = s.icon
            return (
              <li key={c.name} className="px-5 py-4">
                <div className="mb-3 flex items-center justify-between gap-3">
                  <p className="min-w-0 truncate font-medium">{c.name}</p>
                  <p className={cn('flex shrink-0 items-center gap-1.5 text-sm font-medium', s.className)}>
                    <Icon className="size-4" aria-hidden />
                    {s.label}
                  </p>
                </div>
                <UptimeBars
                  buckets={visible.map((d) => ({
                    start: d.date,
                    end: new Date(Date.parse(d.date) + 86_400_000).toISOString(),
                    uptimePct: d.uptimePct,
                    downtimeSeconds: d.downtimeSeconds,
                  }))}
                  startLabel={`${shownDays} days ago`}
                  legend={false}
                  compact
                  centerLabel={uptime === null ? 'No data yet' : `${formatUptime(uptime)} uptime`}
                />
              </li>
            )
          })}
        </ul>
        <div className="border-t border-zinc-200 px-5 py-3 dark:border-zinc-800">
          <UptimeLegend />
        </div>
      </section>

      <section className="mt-10" aria-labelledby="past-incidents">
        <h2 id="past-incidents" className="text-lg font-semibold">
          Past incidents
        </h2>
        <ol className="mt-4 space-y-5">
          {days.map((d) => (
            <li key={d.label}>
              <h3 className="border-b border-zinc-200 pb-1.5 text-sm font-semibold dark:border-zinc-800">{d.label}</h3>
              {d.items.length === 0 ? (
                <p className="mt-2 text-sm text-zinc-500 dark:text-zinc-400">No incidents reported.</p>
              ) : (
                <ul className="mt-2 space-y-3">
                  {d.items.map((i) => (
                    <li key={i.id}>
                      <p
                        className={cn(
                          'font-medium',
                          i.status === 'OPEN' && (i.recovering ? 'text-sky-700 dark:text-sky-400' : 'text-red-600 dark:text-red-400'),
                        )}
                      >
                        {i.componentName} outage
                        {i.status === 'OPEN' && (i.recovering ? ' — recovering' : ' — investigating')}
                      </p>
                      <p className="text-sm text-zinc-500 dark:text-zinc-400">
                        {i.status === 'OPEN'
                          ? `Started ${formatTime(i.startedAt)} · ongoing for ${formatDuration(incidentDurationSeconds(i))}`
                          : `${formatTime(i.startedAt)} – ${formatTime(i.resolvedAt!)} · resolved after ${formatDuration(incidentDurationSeconds(i))}`}
                      </p>
                    </li>
                  ))}
                </ul>
              )}
            </li>
          ))}
        </ol>
      </section>

      <footer className="mt-12 flex flex-col items-center gap-1 border-t border-zinc-200 pt-6 text-xs text-zinc-500 sm:flex-row sm:justify-between dark:border-zinc-800 dark:text-zinc-400">
        <p>
          Updated {formatTime(page.generatedAt)} · refreshes every minute
          {error && <span className="text-amber-600 dark:text-amber-400"> · last refresh failed</span>}
        </p>
        <p>
          Powered by{' '}
          <Link to="/signup" className="font-medium text-zinc-700 hover:underline dark:text-zinc-300">
            PulseGuard
          </Link>
        </p>
      </footer>
    </Shell>
  )
}

function Shell({ children }: { children: ReactNode }) {
  return <div className="mx-auto min-h-dvh max-w-3xl px-4 py-10 sm:px-6 sm:py-14">{children}</div>
}

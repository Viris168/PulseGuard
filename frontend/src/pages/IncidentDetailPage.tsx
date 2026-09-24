import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { AlertCircle, ArrowLeft, ArrowRight, Sparkles } from 'lucide-react'
import { summarizeIncident } from '../api/ai'
import { loadErrorMessage } from '../api/errors'
import { getIncident } from '../api/incidents'
import { listChecks, listPings } from '../api/monitors'
import type { Check, Ping } from '../types/check'
import type { IncidentDetail } from '../types/incident'
import { formatDay, formatDuration, formatTime, incidentDurationSeconds } from '../lib/format'
import { IncidentStatusPill } from '../components/incidents/IncidentStatusPill'
import { IncidentTimeline } from '../components/incidents/IncidentTimeline'
import { ChecksTable } from '../components/monitors/ChecksTable'
import { PingsTable } from '../components/monitors/PingsTable'
import { ButtonLink } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { EmptyState } from '../components/ui/EmptyState'
import { Spinner } from '../components/ui/Spinner'
import { StatCard } from '../components/ui/StatCard'

const CHECK_LIMIT = 60
// Show a few healthy checks either side so the failure streak has context.
const CONTEXT_CHECKS = 3

export function IncidentDetailPage() {
  const id = Number(useParams().id)
  const [incident, setIncident] = useState<IncidentDetail | null>(null)
  const [checks, setChecks] = useState<Check[] | null>(null)
  const [pings, setPings] = useState<Ping[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [summary, setSummary] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    getIncident(id)
      .then((inc) => {
        if (cancelled) return
        setIncident(inc)
        summarizeIncident(inc).then((text) => !cancelled && setSummary(text))
        const pad = CONTEXT_CHECKS * inc.intervalSeconds * 1000
        const end = inc.resolvedAt ? Date.parse(inc.resolvedAt) + pad : Date.now()
        const query = {
          // Heartbeats: include the last on-time ping before the miss.
          from: new Date(Date.parse(inc.startedAt) - pad - (inc.monitorType === 'HEARTBEAT' ? inc.intervalSeconds * 1000 : 0)).toISOString(),
          to: new Date(end).toISOString(),
          limit: CHECK_LIMIT,
          order: 'asc' as const,
        }
        return inc.monitorType === 'HEARTBEAT'
          ? listPings(inc.monitorId, query).then((p) => !cancelled && setPings(p))
          : listChecks(inc.monitorId, query).then((c) => !cancelled && setChecks(c))
      })
      .catch((e: unknown) => !cancelled && setError(loadErrorMessage(e, 'incident')))
    return () => {
      cancelled = true
    }
  }, [id])

  const back = (
    <Link
      to="/incidents"
      className="mb-4 inline-flex items-center gap-1.5 text-sm text-zinc-500 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-white"
    >
      <ArrowLeft className="size-4" aria-hidden />
      Back to incidents
    </Link>
  )

  if (error) {
    return (
      <>
        {back}
        <Card>
          <EmptyState
            icon={AlertCircle}
            title="Incident not found"
            description={error}
            action={<ButtonLink to="/incidents" variant="secondary">Back to incidents</ButtonLink>}
          />
        </Card>
      </>
    )
  }

  if (!incident) {
    return (
      <>
        {back}
        <div className="flex justify-center py-24 text-zinc-400">
          <Spinner />
        </div>
      </>
    )
  }

  const open = incident.status === 'OPEN'
  const alerts = incident.timeline.filter((e) => e.type === 'NOTIFIED')
  const failedAlerts = alerts.filter((e) => e.type === 'NOTIFIED' && e.status === 'FAILED').length
  const failedChecks = checks?.filter((c) => c.result === 'DOWN').length

  return (
    <>
      {back}

      <div className="mb-6">
        <div className="flex items-center gap-3">
          <IncidentStatusPill status={incident.status} />
          <span className="text-sm text-zinc-400 dark:text-zinc-500">Incident #{incident.id}</span>
        </div>
        <h1 className="mt-2 text-2xl font-semibold tracking-tight break-words">{incident.cause ?? 'Unknown cause'}</h1>
        <Link
          to={`/monitors/${incident.monitorId}`}
          className="group mt-1.5 inline-flex max-w-full items-center gap-1.5 text-sm text-zinc-500 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-white"
        >
          <span className="font-medium text-zinc-700 group-hover:underline dark:text-zinc-300">{incident.monitorName}</span>
          <span className="font-mono text-xs text-zinc-400 dark:text-zinc-500">{incident.monitorMethod}</span>
          <span className="truncate">{incident.monitorUrl}</span>
          <ArrowRight className="size-3.5 shrink-0" aria-hidden />
        </Link>
      </div>

      <div className="mb-4 rounded-xl border border-emerald-200 bg-gradient-to-br from-emerald-50 to-white p-4 sm:p-5 dark:border-emerald-500/20 dark:from-emerald-500/10 dark:to-zinc-900">
        <p className="flex items-center gap-2 text-sm font-semibold text-emerald-800 dark:text-emerald-300">
          <Sparkles className="size-4" aria-hidden />
          Summary
          <span className="rounded-full bg-white/70 px-1.5 py-0.5 text-[10px] font-medium text-emerald-700 uppercase dark:bg-zinc-900/60 dark:text-emerald-400">
            AI · preview
          </span>
        </p>
        {summary ? (
          <p className="mt-2 text-sm leading-relaxed text-zinc-700 dark:text-zinc-300">{summary}</p>
        ) : (
          <div className="mt-3 space-y-2" aria-label="Writing summary">
            <div className="h-3 w-full animate-pulse rounded bg-emerald-100 dark:bg-emerald-500/10" />
            <div className="h-3 w-4/5 animate-pulse rounded bg-emerald-100 dark:bg-emerald-500/10" />
          </div>
        )}
      </div>

      <div className="grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-4">
        <StatCard
          label="Duration"
          value={formatDuration(incidentDurationSeconds(incident))}
          sub={open ? 'and counting' : 'first failure to resolve'}
          tone={open ? 'bad' : undefined}
        />
        <StatCard label="Started" value={formatTime(incident.startedAt)} sub={`${formatDay(incident.startedAt)} · first failed check`} />
        <StatCard
          label="Resolved"
          value={incident.resolvedAt ? formatTime(incident.resolvedAt) : 'Not yet'}
          sub={
            incident.resolvedAt
              ? `${formatDay(incident.resolvedAt)} · ${incident.monitorType === 'HEARTBEAT' ? 'on the next ping' : 'after 2 passing checks'}`
              : 'still open'
          }
        />
        <StatCard
          label="Alerts delivered"
          value={alerts.length ? `${alerts.length - failedAlerts} of ${alerts.length}` : 'None yet'}
          sub={
            failedAlerts ? (
              <span className="text-red-600 dark:text-red-400">{failedAlerts} failed to deliver</span>
            ) : alerts.length ? (
              'all delivered'
            ) : (
              'sent when the incident opens'
            )
          }
        />
      </div>

      <div className="mt-6 grid gap-4 lg:grid-cols-5">
        <Card className="p-4 sm:p-5 lg:col-span-2">
          <h2 className="mb-4 text-base font-semibold">Timeline</h2>
          <IncidentTimeline incident={incident} />
        </Card>

        <Card className="overflow-hidden lg:col-span-3">
          <div className="p-4 pb-3 sm:p-5 sm:pb-3">
            <h2 className="text-base font-semibold">{incident.monitorType === 'HEARTBEAT' ? 'Pings' : 'Checks'}</h2>
            <p className="mt-0.5 text-xs text-zinc-500 dark:text-zinc-400">
              {incident.monitorType === 'HEARTBEAT'
                ? 'Pings around the incident, oldest first. The gap is the outage.'
                : checks
                  ? `${failedChecks} failed of ${checks.length} shown · oldest first${checks.length === CHECK_LIMIT ? ` · first ${CHECK_LIMIT}` : ''}`
                  : 'Loading…'}
            </p>
          </div>
          {incident.monitorType === 'HEARTBEAT' ? (
            <PingsTable pings={pings} emptyText="No pings in this window." />
          ) : (
            <ChecksTable
              checks={checks}
              emptyText="No checks recorded for this window."
              highlight={{ from: incident.startedAt, to: incident.resolvedAt }}
            />
          )}
        </Card>
      </div>
    </>
  )
}

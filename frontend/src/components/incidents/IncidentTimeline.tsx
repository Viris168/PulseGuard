import type { ReactNode } from 'react'
import {
  AlertTriangle,
  CheckCircle2,
  CircleDot,
  Hash,
  HeartPulse,
  Mail,
  RefreshCw,
  Send,
  Smartphone,
  Webhook,
  XCircle,
  type LucideIcon,
} from 'lucide-react'
import type { ChannelType, IncidentDetail, IncidentEvent } from '../../types/incident'
import { cn, formatDateTime, formatDuration, incidentDurationSeconds } from '../../lib/format'

const channelIcon: Record<ChannelType, LucideIcon> = {
  EMAIL: Mail,
  SLACK: Hash,
  TELEGRAM: Send,
  SMS: Smartphone,
  WEBHOOK: Webhook,
}

const channelName: Record<ChannelType, string> = {
  EMAIL: 'Email',
  SLACK: 'Slack',
  TELEGRAM: 'Telegram',
  SMS: 'SMS',
  WEBHOOK: 'Webhook',
}

type Tone = 'red' | 'green' | 'sky' | 'zinc'

const toneClass: Record<Tone, string> = {
  red: 'bg-red-100 text-red-600 dark:bg-red-500/15 dark:text-red-400',
  green: 'bg-emerald-100 text-emerald-600 dark:bg-emerald-500/15 dark:text-emerald-400',
  sky: 'bg-sky-100 text-sky-600 dark:bg-sky-500/15 dark:text-sky-400',
  zinc: 'bg-zinc-100 text-zinc-500 dark:bg-zinc-800 dark:text-zinc-400',
}

interface Row {
  icon: LucideIcon
  tone: Tone
  title: string
  body?: ReactNode
  at?: string
  pending?: boolean
}

function describe(e: IncidentEvent, incident: IncidentDetail): Row {
  const heartbeat = incident.monitorType === 'HEARTBEAT'
  switch (e.type) {
    case 'CHECK_FAILED':
      return { icon: XCircle, tone: 'red', title: heartbeat ? 'Ping missed' : 'First failed check', body: e.detail, at: e.at }
    case 'OPENED':
      return {
        icon: AlertTriangle,
        tone: 'red',
        title: 'Incident opened',
        body: heartbeat
          ? 'The grace period passed with no ping. Monitor marked down.'
          : `After ${e.failedChecks} failed checks in a row. Monitor marked down.`,
        at: e.at,
      }
    case 'NOTIFIED': {
      const kind = e.event === 'OPENED' ? 'Down alert' : 'Recovery alert'
      return {
        icon: channelIcon[e.channel],
        tone: e.status === 'FAILED' ? 'red' : 'zinc',
        title: `${kind} ${e.status === 'SENT' ? 'sent' : 'failed'} · ${channelName[e.channel]}`,
        body: (
          <>
            {e.target}
            {e.error && <span className="mt-0.5 block text-red-600 dark:text-red-400">{e.error}</span>}
          </>
        ),
        at: e.at,
      }
    }
    case 'CHECK_PASSED':
      return heartbeat
        ? { icon: HeartPulse, tone: 'green', title: 'Ping received', body: 'The job checked in again.', at: e.at }
        : { icon: RefreshCw, tone: 'sky', title: 'Check passed', body: 'Monitor recovering.', at: e.at }
    case 'RESOLVED':
      return {
        icon: CheckCircle2,
        tone: 'green',
        title: 'Resolved',
        body: heartbeat
          ? `Resolved by the next ping. Down for ${formatDuration(incidentDurationSeconds(incident))}.`
          : `After ${e.passedChecks} passing checks in a row. Down for ${formatDuration(incidentDurationSeconds(incident))}.`,
        at: e.at,
      }
  }
}

/** Offset from the first event, e.g. "+2m". Easier to scan than repeated timestamps. */
function offset(at: string, start: string): string {
  const s = (Date.parse(at) - Date.parse(start)) / 1000
  return s < 1 ? 'Start' : `+${formatDuration(s)}`
}

export function IncidentTimeline({ incident }: { incident: IncidentDetail }) {
  const rows = incident.timeline.map((e) => describe(e, incident))
  const origin = incident.timeline[0]?.at ?? incident.startedAt

  if (incident.status === 'OPEN') {
    const recovering = incident.timeline.at(-1)?.type === 'CHECK_PASSED'
    const heartbeat = incident.monitorType === 'HEARTBEAT'
    rows.push({
      icon: CircleDot,
      tone: recovering ? 'sky' : 'red',
      title: heartbeat ? 'Waiting for a ping' : recovering ? 'Waiting for the next check' : 'Still failing',
      body: heartbeat
        ? "Resolves the moment your job pings again. You'll get a recovery alert."
        : recovering
          ? 'One more passing check resolves this incident.'
          : `Checking every ${formatDuration(incident.intervalSeconds)}. You'll get a recovery alert when it's back.`,
      pending: true,
    })
  }

  return (
    <ol className="relative">
      {rows.map((r, idx) => {
        const Icon = r.icon
        const last = idx === rows.length - 1
        return (
          <li key={idx} className="relative flex gap-3 pb-5 last:pb-0">
            {!last && <span className="absolute top-8 bottom-0 left-[15px] w-px bg-zinc-200 dark:bg-zinc-800" aria-hidden />}
            <span className={cn('relative flex size-8 shrink-0 items-center justify-center rounded-full', toneClass[r.tone])}>
              <Icon className={cn('size-4', r.pending && 'animate-pulse')} aria-hidden />
            </span>
            <div className="min-w-0 flex-1 pt-1">
              <div className="flex items-baseline justify-between gap-2">
                <p className="text-sm font-medium">{r.title}</p>
                {r.at && (
                  <span className="shrink-0 text-xs text-zinc-500 tabular-nums dark:text-zinc-400" title={formatDateTime(r.at)}>
                    {offset(r.at, origin)}
                  </span>
                )}
              </div>
              {r.body && <div className="mt-0.5 text-sm break-words text-zinc-500 dark:text-zinc-400">{r.body}</div>}
              {r.at && <p className="mt-0.5 text-xs text-zinc-400 dark:text-zinc-500">{formatDateTime(r.at)}</p>}
            </div>
          </li>
        )
      })}
    </ol>
  )
}

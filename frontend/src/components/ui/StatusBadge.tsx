import type { DisplayStatus } from '../../types/monitor'
import { cn } from '../../lib/format'

const styles: Record<DisplayStatus, { label: string; badge: string; dot: string; pulse?: boolean }> = {
  UP: {
    label: 'Up',
    badge: 'bg-emerald-50 text-emerald-700 ring-emerald-600/20 dark:bg-emerald-500/10 dark:text-emerald-400 dark:ring-emerald-500/30',
    dot: 'bg-emerald-500',
  },
  DOWN: {
    label: 'Down',
    badge: 'bg-red-50 text-red-700 ring-red-600/20 dark:bg-red-500/10 dark:text-red-400 dark:ring-red-500/30',
    dot: 'bg-red-500',
    pulse: true,
  },
  SUSPICIOUS: {
    label: 'Degraded',
    badge: 'bg-amber-50 text-amber-800 ring-amber-600/20 dark:bg-amber-500/10 dark:text-amber-400 dark:ring-amber-500/30',
    dot: 'bg-amber-500',
  },
  RECOVERING: {
    label: 'Recovering',
    badge: 'bg-sky-50 text-sky-700 ring-sky-600/20 dark:bg-sky-500/10 dark:text-sky-400 dark:ring-sky-500/30',
    dot: 'bg-sky-500',
  },
  PENDING: {
    label: 'Waiting',
    badge: 'bg-violet-50 text-violet-700 ring-violet-600/20 dark:bg-violet-500/10 dark:text-violet-300 dark:ring-violet-500/30',
    dot: 'bg-violet-400',
  },
  PAUSED: {
    label: 'Paused',
    badge: 'bg-zinc-100 text-zinc-600 ring-zinc-500/20 dark:bg-zinc-800 dark:text-zinc-400 dark:ring-zinc-600/40',
    dot: 'bg-zinc-400',
  },
}

export function StatusBadge({ status }: { status: DisplayStatus }) {
  const s = styles[status]
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium whitespace-nowrap ring-1 ring-inset',
        s.badge,
      )}
    >
      <span className="relative flex size-2">
        {s.pulse && <span className={cn('absolute inline-flex size-full animate-ping rounded-full opacity-75', s.dot)} />}
        <span className={cn('relative inline-flex size-2 rounded-full', s.dot)} />
      </span>
      {s.label}
    </span>
  )
}

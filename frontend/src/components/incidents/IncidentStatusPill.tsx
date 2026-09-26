import { CheckCircle2 } from 'lucide-react'
import type { IncidentStatus } from '../../types/incident'

export function IncidentStatusPill({ status }: { status: IncidentStatus }) {
  return status === 'OPEN' ? (
    <span className="inline-flex items-center gap-1.5 text-xs font-medium text-red-600 dark:text-red-400">
      <span className="size-1.5 animate-pulse rounded-full bg-red-500" aria-hidden />
      Ongoing
    </span>
  ) : (
    <span className="inline-flex items-center gap-1.5 text-xs font-medium text-zinc-500 dark:text-zinc-400">
      <CheckCircle2 className="size-3.5" aria-hidden />
      Resolved
    </span>
  )
}

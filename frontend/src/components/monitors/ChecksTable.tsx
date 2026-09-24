import { CheckCircle2, XCircle } from 'lucide-react'
import type { Check } from '../../types/check'
import { cn, formatDateTime, formatMs } from '../../lib/format'
import { Spinner } from '../ui/Spinner'

interface Props {
  checks: Check[] | null
  emptyText?: string
  /** Rows whose time falls in [from, to) get a subtle highlight, e.g. an incident window. */
  highlight?: { from: string; to: string | null }
}

export function ChecksTable({ checks, emptyText = 'No checks yet — the first one runs shortly.', highlight }: Props) {
  if (!checks) {
    return (
      <div className="flex justify-center py-12 text-zinc-400">
        <Spinner />
      </div>
    )
  }
  if (!checks.length) {
    return <p className="px-5 pb-8 pt-4 text-center text-sm text-zinc-500 dark:text-zinc-400">{emptyText}</p>
  }
  return (
    <div className="max-h-[420px] overflow-auto border-t border-zinc-200 dark:border-zinc-800">
      <table className="w-full text-left text-sm">
        <thead className="sticky top-0 bg-zinc-50 text-xs text-zinc-500 dark:bg-zinc-800/80 dark:text-zinc-400">
          <tr>
            <th className="px-4 py-2 font-medium sm:pl-5">Time</th>
            <th className="px-4 py-2 font-medium">Result</th>
            <th className="px-4 py-2 text-right font-medium">Code</th>
            <th className="px-4 py-2 text-right font-medium">Response</th>
            <th className="px-4 py-2 font-medium sm:pr-5">Error</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-zinc-100 dark:divide-zinc-800">
          {checks.map((c) => (
            <tr
              key={c.id}
              className={cn(
                highlight &&
                  c.checkedAt >= highlight.from &&
                  (highlight.to === null || c.checkedAt < highlight.to) &&
                  'bg-red-50/60 dark:bg-red-500/5',
              )}
            >
              <td className="px-4 py-2 whitespace-nowrap text-zinc-600 tabular-nums sm:pl-5 dark:text-zinc-400" title={c.checkedAt}>
                {formatDateTime(c.checkedAt)}
              </td>
              <td className="px-4 py-2">
                {c.result === 'UP' ? (
                  <span className="inline-flex items-center gap-1.5 text-emerald-700 dark:text-emerald-400">
                    <CheckCircle2 className="size-4" aria-hidden /> Up
                  </span>
                ) : (
                  <span className="inline-flex items-center gap-1.5 text-red-600 dark:text-red-400">
                    <XCircle className="size-4" aria-hidden /> Down
                  </span>
                )}
              </td>
              <td className="px-4 py-2 text-right tabular-nums">{c.statusCode ?? '—'}</td>
              <td className="px-4 py-2 text-right whitespace-nowrap tabular-nums">{formatMs(c.responseTimeMs)}</td>
              <td className="max-w-64 truncate px-4 py-2 text-zinc-500 sm:pr-5 dark:text-zinc-400" title={c.errorMessage ?? undefined}>
                {c.errorMessage ?? ''}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}


import { HeartPulse } from 'lucide-react'
import type { Ping } from '../../types/check'
import { formatDateTime, timeAgo } from '../../lib/format'
import { Spinner } from '../ui/Spinner'

export function PingsTable({ pings, emptyText = 'No pings yet. Send a test ping or run your job.' }: { pings: Ping[] | null; emptyText?: string }) {
  if (!pings) {
    return (
      <div className="flex justify-center py-12 text-zinc-400">
        <Spinner />
      </div>
    )
  }
  if (!pings.length) {
    return <p className="px-5 pt-4 pb-8 text-center text-sm text-zinc-500 dark:text-zinc-400">{emptyText}</p>
  }
  return (
    <div className="max-h-[420px] overflow-auto border-t border-zinc-200 dark:border-zinc-800">
      <table className="w-full text-left text-sm">
        <thead className="sticky top-0 bg-zinc-50 text-xs text-zinc-500 dark:bg-zinc-800/80 dark:text-zinc-400">
          <tr>
            <th className="px-4 py-2 font-medium sm:pl-5">Received</th>
            <th className="px-4 py-2 font-medium" />
            <th className="px-4 py-2 font-medium sm:pr-5">From</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-zinc-100 dark:divide-zinc-800">
          {pings.map((p) => (
            <tr key={p.id}>
              <td className="px-4 py-2 whitespace-nowrap tabular-nums sm:pl-5">
                <span className="inline-flex items-center gap-2">
                  <HeartPulse className="size-4 text-emerald-600 dark:text-emerald-400" aria-hidden />
                  {formatDateTime(p.receivedAt)}
                </span>
              </td>
              <td className="px-4 py-2 text-xs whitespace-nowrap text-zinc-500 dark:text-zinc-400">{timeAgo(p.receivedAt)}</td>
              <td className="px-4 py-2 font-mono text-xs text-zinc-500 sm:pr-5 dark:text-zinc-400">{p.sourceIp}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

import type { ReactNode } from 'react'
import { ArrowDownRight, ArrowUpRight, Minus } from 'lucide-react'
import type { Delta } from '../../lib/delta'
import { cn } from '../../lib/format'
import { Card } from './Card'

interface StatCardProps {
  label: string
  /** null renders a loading placeholder. */
  value: ReactNode | null
  sub?: ReactNode
  tone?: 'good' | 'bad'
  /** Change vs a named period; colour = direction × whether that direction is good. */
  delta?: Delta | null
  /** e.g. "vs previous 24h" */
  deltaLabel?: string
}

export function StatCard({ label, value, sub, tone, delta, deltaLabel }: StatCardProps) {
  const DeltaIcon = delta?.direction === 'up' ? ArrowUpRight : delta?.direction === 'down' ? ArrowDownRight : Minus
  return (
    <Card className="p-4 sm:p-5">
      <p className="text-xs font-medium text-zinc-500 sm:text-sm dark:text-zinc-400">{label}</p>
      {value === null ? (
        <div className="mt-2 h-8 w-16 animate-pulse rounded bg-zinc-100 dark:bg-zinc-800" />
      ) : (
        // Proportional figures: tabular digits look loose at display sizes.
        <p
          className={cn(
            'mt-1 text-2xl font-semibold sm:text-3xl',
            tone === 'good' && 'text-emerald-600 dark:text-emerald-400',
            tone === 'bad' && 'text-red-600 dark:text-red-400',
          )}
        >
          {value}
        </p>
      )}
      {delta && value !== null && (
        <p
          className={cn(
            'mt-1 flex flex-wrap items-center gap-x-1 text-xs font-medium',
            delta.good === true && 'text-emerald-700 dark:text-emerald-400',
            delta.good === false && 'text-red-600 dark:text-red-400',
            delta.good === null && 'text-zinc-500 dark:text-zinc-400',
          )}
        >
          <DeltaIcon className="size-3.5 shrink-0" aria-hidden />
          <span className="sr-only">{delta.direction === 'up' ? 'Up' : delta.direction === 'down' ? 'Down' : ''}</span>
          {delta.text}
          {deltaLabel && <span className="font-normal text-zinc-500 dark:text-zinc-400">{deltaLabel}</span>}
        </p>
      )}
      {sub && value !== null && <p className="mt-1 text-xs text-zinc-500 dark:text-zinc-400">{sub}</p>}
    </Card>
  )
}

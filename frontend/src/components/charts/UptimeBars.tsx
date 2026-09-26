import { useState, type KeyboardEvent } from 'react'
import type { StatsRange, UptimeBucket } from '../../types/check'
import { cn, formatDuration, formatSpan, formatUptime } from '../../lib/format'
import { ChartTooltip } from './ChartTooltip'
import { useElementWidth } from './useElementWidth'

// Status colours carry meaning here (good / warning / critical), always paired with the legend labels.
const levels = [
  { test: (u: number) => u >= 99.9, label: '≥ 99.9%', cell: 'bg-emerald-500' },
  { test: (u: number) => u >= 99, label: '99–99.9%', cell: 'bg-amber-400' },
  { test: () => true, label: '< 99%', cell: 'bg-red-500' },
]
const noData = 'bg-zinc-200 dark:bg-zinc-800'

const cellClass = (u: number | null) => (u === null ? noData : levels.find((l) => l.test(u))!.cell)

const rangeStartLabel: Record<StatsRange, string> = {
  '24h': '24 hours ago',
  '7d': '7 days ago',
  '30d': '30 days ago',
}

interface Props {
  buckets: UptimeBucket[]
  /** Picks the left-edge label; or pass `startLabel` directly. */
  range?: StatsRange
  startLabel?: string
  /** Hide when several strips share one legend (see UptimeLegend). */
  legend?: boolean
  /** Shorter cells for dense lists. */
  compact?: boolean
  /** Text between the start and "Now" labels, e.g. the period's uptime. */
  centerLabel?: string
}

/** Status-page style strip: one cell per bucket, coloured by uptime. */
export function UptimeBars({ buckets, range, startLabel, legend = true, compact = false, centerLabel }: Props) {
  const [ref, width] = useElementWidth<HTMLDivElement>()
  const [active, setActive] = useState<number | null>(null)
  const n = buckets.length

  function onKeyDown(e: KeyboardEvent) {
    if (e.key === 'ArrowLeft') setActive((a) => Math.max(0, (a ?? n) - 1))
    else if (e.key === 'ArrowRight') setActive((a) => Math.min(n - 1, (a ?? -1) + 1))
    else return
    e.preventDefault()
  }

  const hovered = active === null ? null : buckets[active]
  const cellCenter = active === null || !n ? 0 : ((active + 0.5) / n) * width

  return (
    <div>
      <div ref={ref} className="relative">
        <div
          className={cn(
            'flex gap-[2px] rounded-sm outline-none focus-visible:ring-2 focus-visible:ring-emerald-600/60 focus-visible:ring-offset-2 dark:focus-visible:ring-offset-zinc-900',
            compact ? 'h-7' : 'h-8',
          )}
          tabIndex={0}
          role="group"
          aria-label="Uptime history. Use left and right arrow keys to read each period."
          onPointerLeave={() => setActive(null)}
          onFocus={() => setActive((a) => a ?? n - 1)}
          onBlur={() => setActive(null)}
          onKeyDown={onKeyDown}
        >
          {buckets.map((b, i) => (
            <div
              key={b.start}
              onPointerEnter={() => setActive(i)}
              className={cn(
                'h-full min-w-0 flex-1 rounded-[2px] transition-opacity',
                cellClass(b.uptimePct),
                active !== null && active !== i && 'opacity-60',
              )}
            />
          ))}
        </div>

        {hovered && (
          <ChartTooltip x={cellCenter} width={width} above>
            <p className="text-sm font-semibold text-zinc-900 tabular-nums dark:text-white">
              {hovered.uptimePct === null ? 'No data' : `${formatUptime(hovered.uptimePct)} uptime`}
            </p>
            <p className="mt-0.5 text-zinc-500 dark:text-zinc-400">{formatSpan(hovered.start, hovered.end)}</p>
            {hovered.downtimeSeconds > 0 && (
              <p className="mt-1 text-zinc-700 dark:text-zinc-300">Down for {formatDuration(hovered.downtimeSeconds)}</p>
            )}
          </ChartTooltip>
        )}
      </div>

      <div className="mt-2 flex justify-between text-xs text-zinc-500 dark:text-zinc-400">
        <span>{startLabel ?? (range ? rangeStartLabel[range] : '')}</span>
        {centerLabel && <span className="font-medium text-zinc-700 dark:text-zinc-300">{centerLabel}</span>}
        <span>Now</span>
      </div>

      {legend && <UptimeLegend className="mt-3" />}

      <p className="sr-only" aria-live="polite">
        {hovered ? `${formatSpan(hovered.start, hovered.end)}: ${hovered.uptimePct === null ? 'no data' : formatUptime(hovered.uptimePct)}` : ''}
      </p>
    </div>
  )
}

export function UptimeLegend({ className }: { className?: string }) {
  return (
    <ul className={cn('flex flex-wrap gap-x-4 gap-y-1 text-xs text-zinc-600 dark:text-zinc-400', className)}>
      {levels.map((l) => (
        <li key={l.label} className="flex items-center gap-1.5">
          <span className={cn('size-2.5 rounded-[2px]', l.cell)} aria-hidden />
          {l.label}
        </li>
      ))}
      <li className="flex items-center gap-1.5">
        <span className={cn('size-2.5 rounded-[2px]', noData)} aria-hidden />
        No data
      </li>
    </ul>
  )
}

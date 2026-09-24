import { useState, type KeyboardEvent, type PointerEvent } from 'react'
import type { SeriesPoint, StatsRange } from '../../types/check'
import { formatDay, formatMs, formatSpan, formatTime, formatUptime } from '../../lib/format'
import { ChartTooltip } from './ChartTooltip'
import { useElementWidth } from './useElementWidth'

const PLOT_H = 200
const AXIS_H = 24 // x-axis label band, included in the SVG height so labels never clip
const PAD_L = 48 // room for y tick labels
const PAD_R = 8
const PAD_T = 8

/** Rounds a raw step up to 1/2/2.5/5 × 10ⁿ so ticks land on clean numbers. */
function niceStep(raw: number): number {
  const p = 10 ** Math.floor(Math.log10(raw))
  const f = raw / p
  return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * p
}

const tickLabel = (ms: number) => (ms >= 1000 ? `${ms / 1000}s` : `${ms}ms`)

interface Props {
  points: SeriesPoint[]
  range: StatsRange
}

/**
 * Single-series response time line with an area wash. Buckets with downtime get a
 * status-red band behind the line; fully-down buckets leave a gap in the line.
 */
export function ResponseTimeChart({ points, range }: Props) {
  const [ref, width] = useElementWidth<HTMLDivElement>()
  const [active, setActive] = useState<number | null>(null)

  const n = points.length
  const plotW = Math.max(0, width - PAD_L - PAD_R)
  const bw = n ? plotW / n : 0
  const values = points.map((p) => p.avgResponseMs)
  const max = Math.max(1, ...values.map((v) => v ?? 0))
  const step = niceStep((max * 1.1) / 4)
  const top = Math.ceil((max * 1.1) / step) * step
  const ticks = Array.from({ length: Math.round(top / step) + 1 }, (_, i) => i * step)

  const xAt = (i: number) => PAD_L + (i + 0.5) * bw
  const yAt = (v: number) => PAD_T + PLOT_H - (v / top) * PLOT_H
  const baseline = PAD_T + PLOT_H

  // Split into contiguous runs so a fully-down bucket breaks the line instead of bridging it.
  const runs: { i: number; v: number }[][] = []
  values.forEach((v, i) => {
    if (v === null) return
    const last = runs[runs.length - 1]
    if (last && last[last.length - 1].i === i - 1) last.push({ i, v })
    else runs.push([{ i, v }])
  })
  const linePath = runs.map((r) => r.map((p, k) => `${k ? 'L' : 'M'}${xAt(p.i)},${yAt(p.v)}`).join('')).join('')
  const areaPath = runs
    .map((r) => `M${xAt(r[0].i)},${baseline}` + r.map((p) => `L${xAt(p.i)},${yAt(p.v)}`).join('') + `L${xAt(r[r.length - 1].i)},${baseline}Z`)
    .join('')

  // Evenly spaced x labels, roughly one per 100px.
  const labelEvery = Math.max(1, Math.ceil(n / Math.max(1, Math.floor(plotW / 100))))
  const xLabels = points
    .map((p, i) => ({ i, text: range === '24h' ? formatTime(p.start) : formatDay(p.start) }))
    .filter(({ i }) => i % labelEvery === Math.floor(labelEvery / 2))

  function onPointerMove(e: PointerEvent<SVGSVGElement>) {
    const x = e.clientX - e.currentTarget.getBoundingClientRect().left
    const i = Math.floor((x - PAD_L) / bw)
    setActive(i >= 0 && i < n ? i : null)
  }

  function onKeyDown(e: KeyboardEvent) {
    if (e.key === 'ArrowLeft') setActive((a) => Math.max(0, (a ?? n) - 1))
    else if (e.key === 'ArrowRight') setActive((a) => Math.min(n - 1, (a ?? -1) + 1))
    else if (e.key === 'Home') setActive(0)
    else if (e.key === 'End') setActive(n - 1)
    else return
    e.preventDefault()
  }

  const hovered = active === null ? null : points[active]

  return (
    <div ref={ref} className="relative">
      {width > 0 && (
        <svg
          width={width}
          height={PAD_T + PLOT_H + AXIS_H}
          className="block touch-pan-y outline-none focus-visible:rounded-md focus-visible:ring-2 focus-visible:ring-emerald-600/60"
          tabIndex={0}
          role="group"
          aria-label="Response time chart. Use left and right arrow keys to read values."
          onPointerMove={onPointerMove}
          onPointerLeave={() => setActive(null)}
          onFocus={() => setActive((a) => a ?? n - 1)}
          onBlur={() => setActive(null)}
          onKeyDown={onKeyDown}
        >
          {/* Downtime bands sit behind everything. */}
          {points.map((p, i) =>
            p.uptimePct !== null && p.uptimePct < 100 ? (
              <rect
                key={`down-${i}`}
                x={PAD_L + i * bw}
                y={PAD_T}
                width={Math.max(bw, 2)}
                height={PLOT_H}
                fill="var(--status-down)"
                opacity={0.14}
              />
            ) : null,
          )}

          {ticks.map((t) => (
            <g key={t}>
              <line x1={PAD_L} x2={width - PAD_R} y1={yAt(t)} y2={yAt(t)} stroke="var(--chart-grid)" strokeWidth={1} />
              <text
                x={PAD_L - 8}
                y={yAt(t)}
                dy="0.32em"
                textAnchor="end"
                className="fill-[var(--chart-axis)] text-[11px] tabular-nums"
              >
                {tickLabel(t)}
              </text>
            </g>
          ))}

          {xLabels.map(({ i, text }) => (
            <text
              key={i}
              x={xAt(i)}
              y={baseline + 16}
              textAnchor="middle"
              className="fill-[var(--chart-axis)] text-[11px] tabular-nums"
            >
              {text}
            </text>
          ))}

          <path d={areaPath} fill="var(--series-1)" opacity={0.1} />
          <path d={linePath} fill="none" stroke="var(--series-1)" strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
          {/* A lone point between two gaps has no line segment — give it a dot so it isn't invisible. */}
          {runs
            .filter((r) => r.length === 1)
            .map(([p]) => (
              <circle key={`solo-${p.i}`} cx={xAt(p.i)} cy={yAt(p.v)} r={2.5} fill="var(--series-1)" />
            ))}

          {hovered && active !== null && (
            <g pointerEvents="none">
              <line x1={xAt(active)} x2={xAt(active)} y1={PAD_T} y2={baseline} stroke="var(--chart-axis)" strokeWidth={1} opacity={0.6} />
              {hovered.avgResponseMs !== null && (
                <circle
                  cx={xAt(active)}
                  cy={yAt(hovered.avgResponseMs)}
                  r={4}
                  fill="var(--series-1)"
                  stroke="var(--chart-surface)"
                  strokeWidth={2}
                />
              )}
            </g>
          )}
        </svg>
      )}

      {hovered && active !== null && (
        <ChartTooltip x={xAt(active)} width={width}>
          <p className="text-sm font-semibold text-zinc-900 tabular-nums dark:text-white">
            {hovered.avgResponseMs !== null
              ? `${formatMs(hovered.avgResponseMs)} avg`
              : hovered.uptimePct === null
                ? 'No data'
                : 'No response'}
          </p>
          <p className="mt-0.5 text-zinc-500 dark:text-zinc-400">{formatSpan(hovered.start, hovered.end)}</p>
          {hovered.uptimePct !== null && hovered.uptimePct < 100 && (
            <p className="mt-1.5 flex items-center gap-1.5 text-zinc-700 dark:text-zinc-300">
              <span className="h-0.5 w-3 rounded-full bg-[var(--status-down)]" aria-hidden />
              {formatUptime(hovered.uptimePct)} uptime
            </p>
          )}
        </ChartTooltip>
      )}

      {/* Mirrors the tooltip for screen readers during keyboard navigation. */}
      <p className="sr-only" aria-live="polite">
        {hovered
          ? `${formatSpan(hovered.start, hovered.end)}: ${hovered.avgResponseMs !== null ? formatMs(hovered.avgResponseMs) : 'no response'}`
          : ''}
      </p>
    </div>
  )
}

import type { ReactNode } from 'react'

/**
 * Floating readout anchored at an x position inside a `relative` chart container.
 * Near the edges it flips its alignment instead of overflowing the card.
 */
export function ChartTooltip({
  x,
  width,
  above = false,
  children,
}: {
  x: number
  width: number
  /** Float above the container instead of overlaying its top edge. */
  above?: boolean
  children: ReactNode
}) {
  const edge = 90
  const translate = x < edge ? '0' : x > width - edge ? '-100%' : '-50%'
  return (
    <div
      className={`pointer-events-none absolute z-10 ${above ? 'bottom-full mb-2' : 'top-0'} w-max max-w-56 rounded-lg border border-zinc-200 bg-white px-3 py-2 text-xs shadow-lg dark:border-zinc-700 dark:bg-zinc-800`}
      style={{ left: x, transform: `translateX(${translate})` }}
    >
      {children}
    </div>
  )
}

import { cn } from '../../lib/format'

interface Props {
  label: string
  used: number
  /** Infinity = unlimited: no bar, just the count. */
  limit: number
}

/** Fill colour carries how close you are to the limit; the track is a lighter step of the same hue. */
export function UsageMeter({ label, used, limit }: Props) {
  const unlimited = limit === Infinity
  const ratio = unlimited ? 0 : Math.min(1, used / limit)
  const tone = ratio >= 1 ? 'red' : ratio >= 0.8 ? 'amber' : 'emerald'
  const track = {
    emerald: 'bg-emerald-100 dark:bg-emerald-500/15',
    amber: 'bg-amber-100 dark:bg-amber-500/15',
    red: 'bg-red-100 dark:bg-red-500/15',
  }[tone]
  const fill = { emerald: 'bg-emerald-500', amber: 'bg-amber-500', red: 'bg-red-500' }[tone]

  return (
    <div>
      <div className="flex items-baseline justify-between gap-3 text-sm">
        <span className="font-medium">{label}</span>
        <span className="text-zinc-600 tabular-nums dark:text-zinc-400">
          {unlimited ? `${used} · Unlimited` : `${used} of ${limit}`}
        </span>
      </div>
      {!unlimited && (
        <div
          className={cn('mt-2 h-2 overflow-hidden rounded-full', track)}
          role="meter"
          aria-label={label}
          aria-valuemin={0}
          aria-valuemax={limit}
          aria-valuenow={used}
        >
          <div className={cn('h-full rounded-full transition-[width]', fill)} style={{ width: `${Math.max(ratio * 100, used ? 4 : 0)}%` }} />
        </div>
      )}
      {!unlimited && ratio >= 1 && (
        <p className="mt-1.5 text-xs text-red-600 dark:text-red-400">Limit reached — upgrade to add more.</p>
      )}
    </div>
  )
}

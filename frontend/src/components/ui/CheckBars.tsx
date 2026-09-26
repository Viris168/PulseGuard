import { cn } from '../../lib/format'

/** Tiny history strip: one bar per recent check, oldest on the left. */
export function CheckBars({ checks, slots = 30 }: { checks: boolean[]; slots?: number }) {
  const padded: (boolean | null)[] = [...Array(Math.max(0, slots - checks.length)).fill(null), ...checks.slice(-slots)]
  const failures = checks.filter((c) => !c).length
  return (
    <div
      className="flex h-6 items-end gap-[2px]"
      role="img"
      aria-label={checks.length ? `Last ${checks.length} checks, ${failures} failed` : 'No checks yet'}
    >
      {padded.map((ok, i) => (
        <span
          key={i}
          className={cn(
            'h-full w-1 rounded-sm',
            ok === null ? 'bg-zinc-200 dark:bg-zinc-800' : ok ? 'bg-emerald-500' : 'bg-red-500',
          )}
        />
      ))}
    </div>
  )
}

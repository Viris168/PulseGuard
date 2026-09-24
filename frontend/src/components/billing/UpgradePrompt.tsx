import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ArrowUpCircle } from 'lucide-react'
import { cn } from '../../lib/format'

interface Props {
  title: string
  children?: ReactNode
  /** Button text; links to the billing page. */
  cta?: string
  className?: string
}

/** Shown wherever a plan limit blocks something, so the way forward is one click away. */
export function UpgradePrompt({ title, children, cta = 'See plans', className }: Props) {
  return (
    <div
      className={cn(
        'flex flex-col gap-3 rounded-xl border border-emerald-200 bg-emerald-50 p-4 sm:flex-row sm:items-center dark:border-emerald-500/30 dark:bg-emerald-500/10',
        className,
      )}
    >
      <ArrowUpCircle className="size-5 shrink-0 text-emerald-600 dark:text-emerald-400" aria-hidden />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-semibold text-emerald-900 dark:text-emerald-100">{title}</p>
        {children && <p className="mt-0.5 text-sm text-emerald-800/80 dark:text-emerald-200/80">{children}</p>}
      </div>
      <Link
        to="/billing"
        className="inline-flex h-8 shrink-0 items-center justify-center rounded-lg bg-emerald-600 px-3 text-sm font-medium text-white hover:bg-emerald-700"
      >
        {cta}
      </Link>
    </div>
  )
}

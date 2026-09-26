import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ArrowLeft } from 'lucide-react'
import { cn } from '../../lib/format'

// Pill-shaped back link: the green arrow tab slides across to fill the button on hover.
export function BackLink({ to, children, className }: { to: string; children: ReactNode; className?: string }) {
  return (
    <Link
      to={to}
      className={cn(
        'group relative mb-4 inline-flex h-11 items-center overflow-hidden rounded-2xl border border-zinc-200 bg-white pr-5 pl-14 text-sm font-semibold text-zinc-900 shadow-sm focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 dark:border-zinc-800 dark:bg-zinc-900 dark:text-white',
        className,
      )}
    >
      <span
        className="absolute inset-y-1 left-1 z-10 flex w-11 items-center justify-center rounded-xl bg-emerald-400 transition-[width] duration-500 group-hover:w-[calc(100%-0.5rem)] group-focus-visible:w-[calc(100%-0.5rem)] motion-reduce:transition-none"
        aria-hidden
      >
        <ArrowLeft className="size-5 text-black" strokeWidth={2.5} />
      </span>
      {children}
    </Link>
  )
}

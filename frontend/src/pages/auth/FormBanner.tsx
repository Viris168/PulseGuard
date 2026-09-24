import { AlertCircle } from 'lucide-react'
import type { ReactNode } from 'react'

export function FormBanner({ children }: { children: ReactNode }) {
  return (
    <div
      className="flex gap-2.5 rounded-lg border border-red-200 bg-red-50 px-3 py-2.5 text-sm text-red-800 dark:border-red-500/30 dark:bg-red-500/10 dark:text-red-200"
      role="alert"
    >
      <AlertCircle className="mt-0.5 size-4 shrink-0" aria-hidden />
      <div>{children}</div>
    </div>
  )
}

import type { FormEvent, ReactNode } from 'react'
import { CheckCircle2 } from 'lucide-react'
import { Card } from '../../components/ui/Card'

interface Props {
  title: string
  description?: ReactNode
  children?: ReactNode
  /** Footer content (buttons). When set with onSubmit, the card is a form. */
  footer?: ReactNode
  onSubmit?: (e: FormEvent) => void
  /** Short confirmation shown in the footer, e.g. "Saved". */
  success?: string | null
  error?: ReactNode
}

/** One settings block: heading, body, and a footer bar for its actions. */
export function SettingsCard({ title, description, children, footer, onSubmit, success, error }: Props) {
  const body = (
    <>
      <div className="p-5 sm:p-6">
        <h2 className="text-base font-semibold">{title}</h2>
        {description && <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">{description}</p>}
        {children && <div className="mt-5">{children}</div>}
      </div>
      {footer && (
        <div className="flex flex-col-reverse gap-3 rounded-b-xl border-t border-zinc-200 bg-zinc-50 px-5 py-3.5 sm:flex-row sm:items-center sm:justify-end sm:px-6 dark:border-zinc-800 dark:bg-zinc-900/60">
          {error && (
            <p className="text-sm text-red-600 sm:mr-auto dark:text-red-400" role="alert">
              {error}
            </p>
          )}
          {success && !error && (
            <p className="flex items-center gap-1.5 text-sm text-emerald-700 sm:mr-auto dark:text-emerald-400" role="status">
              <CheckCircle2 className="size-4" aria-hidden />
              {success}
            </p>
          )}
          {footer}
        </div>
      )}
    </>
  )
  return (
    <Card>
      {onSubmit ? (
        <form onSubmit={onSubmit} noValidate>
          {body}
        </form>
      ) : (
        body
      )}
    </Card>
  )
}

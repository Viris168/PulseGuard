import { Eye, EyeOff } from 'lucide-react'
import { useState, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes, type TextareaHTMLAttributes } from 'react'
import { cn } from '../../lib/format'

const control =
  'block w-full rounded-lg border bg-white px-3 py-2 text-sm text-zinc-900 shadow-xs placeholder:text-zinc-400 focus:outline-2 focus:-outline-offset-1 dark:bg-zinc-950 dark:text-zinc-100'

const controlState = (error?: string) =>
  error
    ? 'border-red-400 focus:outline-red-500 dark:border-red-500/60'
    : 'border-zinc-300 focus:outline-emerald-600 dark:border-zinc-700'

interface FieldProps {
  id: string
  label: string
  hint?: ReactNode
  error?: string
  children: ReactNode
}

export function Field({ id, label, hint, error, children }: FieldProps) {
  return (
    <div>
      <label htmlFor={id} className="mb-1.5 block text-sm font-medium text-zinc-800 dark:text-zinc-200">
        {label}
      </label>
      {children}
      {error ? (
        <p id={`${id}-error`} className="mt-1.5 text-xs text-red-600 dark:text-red-400">
          {error}
        </p>
      ) : (
        hint && <p className="mt-1.5 text-xs text-zinc-500 dark:text-zinc-400">{hint}</p>
      )}
    </div>
  )
}

export function Input({ error, className, ...rest }: InputHTMLAttributes<HTMLInputElement> & { error?: string }) {
  return (
    <input
      aria-invalid={!!error}
      aria-describedby={error ? `${rest.id}-error` : undefined}
      className={cn(control, controlState(error), className)}
      {...rest}
    />
  )
}

export function Select({ error, className, ...rest }: SelectHTMLAttributes<HTMLSelectElement> & { error?: string }) {
  return (
    <select
      aria-invalid={!!error}
      className={cn(control, controlState(error), 'pr-8', className)}
      {...rest}
    />
  )
}

/** Password input with a show/hide toggle. */
export function PasswordInput({ error, className, ...rest }: InputHTMLAttributes<HTMLInputElement> & { error?: string }) {
  const [visible, setVisible] = useState(false)
  return (
    <div className="relative">
      <Input {...rest} type={visible ? 'text' : 'password'} error={error} className={cn('pr-10', className)} />
      <button
        type="button"
        onClick={() => setVisible((v) => !v)}
        className="absolute inset-y-0 right-0 flex w-10 items-center justify-center rounded-r-lg text-zinc-400 hover:text-zinc-700 dark:hover:text-zinc-200"
        aria-label={visible ? 'Hide password' : 'Show password'}
        aria-pressed={visible}
      >
        {visible ? <EyeOff className="size-4" /> : <Eye className="size-4" />}
      </button>
    </div>
  )
}

export function Textarea({ error, className, ...rest }: TextareaHTMLAttributes<HTMLTextAreaElement> & { error?: string }) {
  return (
    <textarea
      aria-invalid={!!error}
      aria-describedby={error ? `${rest.id}-error` : undefined}
      className={cn(control, controlState(error), 'min-h-20 resize-y', className)}
      {...rest}
    />
  )
}

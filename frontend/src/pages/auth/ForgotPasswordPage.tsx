import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { MailCheck } from 'lucide-react'
import { requestPasswordReset } from '../../api/auth'
import { Button } from '../../components/ui/Button'
import { Field, Input } from '../../components/ui/Field'
import { EMAIL_RE, readApiError } from './authForm'
import { FormBanner } from './FormBanner'

/** Asks for a reset link. The reply is the same for every address, registered or not. */
export function ForgotPasswordPage() {
  const [email, setEmail] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [banner, setBanner] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [sentTo, setSentTo] = useState<string | null>(null)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setBanner(null)
    const value = email.trim()
    if (!value) return setError('Email is required')
    if (!EMAIL_RE.test(value)) return setError('Enter a valid email address')
    setError(null)
    setSubmitting(true)
    try {
      await requestPasswordReset(value)
      setSentTo(value)
    } catch (err) {
      const { fields, banner } = readApiError(err)
      setError(fields.email ?? null)
      setBanner(fields.email ? null : banner)
    } finally {
      setSubmitting(false)
    }
  }

  if (sentTo) {
    return (
      <>
        <MailCheck className="size-8 text-emerald-600 dark:text-emerald-400" aria-hidden />
        <h1 className="mt-4 text-2xl font-semibold tracking-tight">Check your email</h1>
        <p className="mt-1.5 text-sm text-zinc-500 dark:text-zinc-400" role="status">
          If an account exists for <strong className="font-medium text-zinc-900 dark:text-zinc-100">{sentTo}</strong>, we've sent a link to
          reset its password. It works once and expires in 30 minutes.
        </p>
        <p className="mt-6 text-sm text-zinc-500 dark:text-zinc-400">
          Nothing arrived? Check spam, or{' '}
          <button type="button" onClick={() => setSentTo(null)} className="font-medium text-emerald-700 hover:underline dark:text-emerald-400">
            try again
          </button>
          .
        </p>
        <p className="mt-6 text-center text-sm">
          <Link to="/login" className="font-medium text-emerald-700 hover:underline dark:text-emerald-400">
            Back to sign in
          </Link>
        </p>
      </>
    )
  }

  return (
    <>
      <h1 className="text-2xl font-semibold tracking-tight">Reset your password</h1>
      <p className="mt-1.5 text-sm text-zinc-500 dark:text-zinc-400">Enter your account's email and we'll send you a link to set a new one.</p>

      <form onSubmit={onSubmit} noValidate className="mt-8 space-y-5">
        {banner && <FormBanner>{banner}</FormBanner>}
        <Field id="email" label="Email" error={error ?? undefined}>
          <Input
            id="email"
            type="email"
            autoComplete="email"
            autoFocus
            value={email}
            onChange={(e) => {
              setEmail(e.target.value)
              setError(null)
            }}
            placeholder="you@company.com"
            error={error ?? undefined}
          />
        </Field>
        <Button type="submit" loading={submitting} className="w-full">
          Send reset link
        </Button>
      </form>

      <p className="mt-6 text-center text-sm text-zinc-500 dark:text-zinc-400">
        Remembered it?{' '}
        <Link to="/login" className="font-medium text-emerald-700 hover:underline dark:text-emerald-400">
          Sign in
        </Link>
      </p>
    </>
  )
}

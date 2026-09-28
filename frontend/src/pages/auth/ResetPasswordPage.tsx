import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { resetPassword } from '../../api/auth'
import { Button } from '../../components/ui/Button'
import { Field, PasswordInput } from '../../components/ui/Field'
import { readApiError } from './authForm'
import { FormBanner } from './FormBanner'

// Same rules as sign-up and ResetPasswordRequest on the server.
const MIN_PASSWORD = 8
const MAX_PASSWORD = 72

/** Where the emailed link lands: /reset-password?token=… */
export function ResetPasswordPage() {
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''
  const navigate = useNavigate()

  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [banner, setBanner] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setBanner(null)
    if (password.length < MIN_PASSWORD) return setError(`Use at least ${MIN_PASSWORD} characters`)
    if (password.length > MAX_PASSWORD) return setError(`Use ${MAX_PASSWORD} characters or fewer`)
    setError(null)
    setSubmitting(true)
    try {
      await resetPassword(token, password)
      navigate('/login', { replace: true, state: { passwordReset: true } })
    } catch (err) {
      const { fields, banner } = readApiError(err)
      setError(fields.newPassword ?? null)
      setBanner(fields.newPassword ? null : (banner ?? fields.token ?? null))
      setSubmitting(false)
    }
  }

  if (!token) {
    return (
      <>
        <h1 className="text-2xl font-semibold tracking-tight">This link is incomplete</h1>
        <p className="mt-1.5 text-sm text-zinc-500 dark:text-zinc-400">
          Open the link from the email exactly as it was sent, or request a new one.
        </p>
        <p className="mt-6 text-sm">
          <Link to="/forgot-password" className="font-medium text-emerald-700 hover:underline dark:text-emerald-400">
            Request a new link
          </Link>
        </p>
      </>
    )
  }

  return (
    <>
      <h1 className="text-2xl font-semibold tracking-tight">Choose a new password</h1>
      <p className="mt-1.5 text-sm text-zinc-500 dark:text-zinc-400">You'll be signed out everywhere and your API keys revoked. Then sign in with the new one.</p>

      <form onSubmit={onSubmit} noValidate className="mt-8 space-y-5">
        {banner && (
          <FormBanner>
            {banner}{' '}
            <Link to="/forgot-password" className="font-medium underline underline-offset-2">
              Request a new link
            </Link>
          </FormBanner>
        )}
        <Field id="password" label="New password" hint={`At least ${MIN_PASSWORD} characters.`} error={error ?? undefined}>
          <PasswordInput
            id="password"
            autoComplete="new-password"
            autoFocus
            value={password}
            onChange={(e) => {
              setPassword(e.target.value)
              setError(null)
            }}
            error={error ?? undefined}
          />
        </Field>
        <Button type="submit" loading={submitting} className="w-full">
          Set new password
        </Button>
      </form>
    </>
  )
}

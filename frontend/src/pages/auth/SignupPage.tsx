import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { Check } from 'lucide-react'
import { ApiError } from '../../api/errors'
import { useAuth } from '../../auth/authContext'
import { Button } from '../../components/ui/Button'
import { Field, Input, PasswordInput } from '../../components/ui/Field'
import { cn } from '../../lib/format'
import { EMAIL_RE, readApiError } from './authForm'
import { FormBanner } from './FormBanner'

type FieldName = 'name' | 'email' | 'password'
type Errors = Partial<Record<FieldName, string>>

// Same limits as RegisterRequest (BCrypt ignores bytes past 72).
const MIN_PASSWORD = 8
const MAX_PASSWORD = 72

function validate(f: Record<FieldName, string>): Errors {
  const e: Errors = {}
  if (!f.name.trim()) e.name = 'Name is required'
  else if (f.name.length > 100) e.name = 'Name must be 100 characters or fewer'
  if (!f.email.trim()) e.email = 'Email is required'
  else if (!EMAIL_RE.test(f.email.trim())) e.email = 'Enter a valid email address'
  if (!f.password) e.password = 'Password is required'
  else if (f.password.length < MIN_PASSWORD) e.password = `Use at least ${MIN_PASSWORD} characters`
  else if (f.password.length > MAX_PASSWORD) e.password = `Use ${MAX_PASSWORD} characters or fewer`
  return e
}

export function SignupPage() {
  const { register } = useAuth()

  const [form, setForm] = useState<Record<FieldName, string>>({ name: '', email: '', password: '' })
  const [errors, setErrors] = useState<Errors>({})
  const [banner, setBanner] = useState<string | null>(null)
  const [emailTaken, setEmailTaken] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  const set = (key: FieldName, value: string) => setForm((f) => ({ ...f, [key]: value }))
  const longEnough = form.password.length >= MIN_PASSWORD

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    const found = validate(form)
    setErrors(found)
    setBanner(null)
    setEmailTaken(false)
    if (Object.keys(found).length) return

    setSubmitting(true)
    try {
      // RedirectIfAuthed takes over as soon as the session exists.
      await register({ name: form.name.trim(), email: form.email.trim(), password: form.password })
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setEmailTaken(true)
        setErrors({ email: 'An account with this email already exists' })
      } else {
        const { fields, banner } = readApiError(err)
        setErrors(fields)
        setBanner(banner)
      }
      setSubmitting(false)
    }
  }

  return (
    <>
      <h1 className="text-2xl font-semibold tracking-tight">Create your account</h1>
      <p className="mt-1.5 text-sm text-zinc-500 dark:text-zinc-400">Free for up to 3 monitors. No credit card needed.</p>

      <form onSubmit={onSubmit} noValidate className="mt-8 space-y-5">
        {banner && <FormBanner>{banner}</FormBanner>}

        <Field id="name" label="Name" error={errors.name}>
          <Input
            id="name"
            autoComplete="name"
            autoFocus
            value={form.name}
            onChange={(e) => set('name', e.target.value)}
            maxLength={100}
            error={errors.name}
          />
        </Field>

        <Field
          id="email"
          label="Work email"
          error={errors.email}
          hint={emailTaken ? undefined : "We'll send incident alerts here."}
        >
          <Input
            id="email"
            type="email"
            autoComplete="email"
            value={form.email}
            onChange={(e) => {
              set('email', e.target.value)
              setEmailTaken(false)
            }}
            placeholder="you@company.com"
            error={errors.email}
          />
        </Field>
        {emailTaken && (
          <p className="-mt-3 text-xs text-zinc-500 dark:text-zinc-400">
            <Link to="/login" className="font-medium text-emerald-700 hover:underline dark:text-emerald-400">
              Sign in instead
            </Link>
          </p>
        )}

        <Field id="password" label="Password" error={errors.password}>
          <PasswordInput
            id="password"
            autoComplete="new-password"
            value={form.password}
            onChange={(e) => set('password', e.target.value)}
            maxLength={MAX_PASSWORD}
            error={errors.password}
          />
        </Field>
        {!errors.password && (
          <p
            className={cn(
              '-mt-3 flex items-center gap-1.5 text-xs transition-colors',
              longEnough ? 'text-emerald-700 dark:text-emerald-400' : 'text-zinc-500 dark:text-zinc-400',
            )}
          >
            <Check className={cn('size-3.5', !longEnough && 'opacity-40')} aria-hidden />
            At least {MIN_PASSWORD} characters
          </p>
        )}

        <Button type="submit" loading={submitting} className="w-full">
          Create account
        </Button>
      </form>

      <p className="mt-6 text-center text-sm text-zinc-500 dark:text-zinc-400">
        Already have an account?{' '}
        <Link to="/login" className="font-medium text-emerald-700 hover:underline dark:text-emerald-400">
          Sign in
        </Link>
      </p>
    </>
  )
}

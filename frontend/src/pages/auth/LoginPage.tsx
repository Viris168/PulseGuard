import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../../auth/authContext'
import { Button } from '../../components/ui/Button'
import { Field, Input, PasswordInput } from '../../components/ui/Field'
import { EMAIL_RE, readApiError } from './authForm'
import { FormBanner } from './FormBanner'

type Errors = Partial<Record<'email' | 'password', string>>

function validate(email: string, password: string): Errors {
  const e: Errors = {}
  if (!email.trim()) e.email = 'Email is required'
  else if (!EMAIL_RE.test(email.trim())) e.email = 'Enter a valid email address'
  if (!password) e.password = 'Password is required'
  return e
}

export function LoginPage() {
  const { login } = useAuth()

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [errors, setErrors] = useState<Errors>({})
  const [banner, setBanner] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    const found = validate(email, password)
    setErrors(found)
    setBanner(null)
    if (Object.keys(found).length) return

    setSubmitting(true)
    try {
      // RedirectIfAuthed takes over as soon as the session exists.
      await login({ email: email.trim(), password })
    } catch (err) {
      const { fields, banner } = readApiError(err)
      setErrors(fields)
      setBanner(banner)
      setSubmitting(false)
    }
  }

  return (
    <>
      <h1 className="text-2xl font-semibold tracking-tight">Sign in</h1>
      <p className="mt-1.5 text-sm text-zinc-500 dark:text-zinc-400">Welcome back. Your monitors kept watch while you were away.</p>

      <form onSubmit={onSubmit} noValidate className="mt-8 space-y-5">
        {banner && <FormBanner>{banner}</FormBanner>}

        <Field id="email" label="Email" error={errors.email}>
          <Input
            id="email"
            type="email"
            autoComplete="email"
            autoFocus
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="you@company.com"
            error={errors.email}
          />
        </Field>

        <Field id="password" label="Password" error={errors.password}>
          <PasswordInput
            id="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            error={errors.password}
          />
        </Field>

        <Button type="submit" loading={submitting} className="w-full">
          Sign in
        </Button>
      </form>

      <p className="mt-6 text-center text-sm text-zinc-500 dark:text-zinc-400">
        New to PulseGuard?{' '}
        <Link to="/signup" className="font-medium text-emerald-700 hover:underline dark:text-emerald-400">
          Create an account
        </Link>
      </p>
    </>
  )
}

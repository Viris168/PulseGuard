import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { CheckCircle2 } from 'lucide-react'
import { confirmEmailChange, me, verifyEmail } from '../../api/auth'
import { confirmChannel } from '../../api/channels'
import { getSession, updateSessionUser } from '../../api/session'
import { Spinner } from '../../components/ui/Spinner'
import { readApiError } from './authForm'
import { FormBanner } from './FormBanner'

type Mode = 'verify' | 'change' | 'channel'

const COPY: Record<Mode, { working: string; done: string; body: string }> = {
  verify: {
    working: 'Verifying your email…',
    done: 'Email verified',
    body: "You'll get an email whenever a monitor goes down and again when it recovers.",
  },
  change: {
    working: 'Confirming your new email…',
    done: 'Email changed',
    body: 'Sign in with your new address from now on. Alerts sent to the old one now go to the new one.',
  },
  channel: {
    working: 'Confirming this address…',
    done: 'Alerts confirmed',
    body: "This address will now get PulseGuard alerts when that account's monitors go down or recover.",
  },
}

/**
 * Where the emailed links land: /verify-email?token=… and /confirm-email?token=…. The link is
 * used once, on arrival; a signed-in tab picks up the change straight away.
 */
export function EmailLinkPage({ mode }: { mode: Mode }) {
  const [params] = useSearchParams()
  const token = params.get('token') ?? ''
  const [state, setState] = useState<'working' | 'done' | 'failed'>(token ? 'working' : 'failed')
  const [error, setError] = useState<string | null>(token ? null : 'This link is incomplete.')
  // The token is single-use: React's dev double-run of effects must not spend it twice.
  const sent = useRef(false)

  useEffect(() => {
    if (!token || sent.current) return
    sent.current = true
    const use = mode === 'verify' ? verifyEmail : mode === 'change' ? confirmEmailChange : confirmChannel
    use(token)
      .then(async () => {
        if (getSession()) updateSessionUser(await me())
        setState('done')
      })
      .catch((err: unknown) => {
        setError(readApiError(err).banner ?? 'This link is invalid or has expired.')
        setState('failed')
      })
  }, [mode, token])

  const copy = COPY[mode]
  const signedIn = !!getSession()

  if (state === 'working') {
    return (
      <div className="flex items-center gap-3 text-sm text-zinc-500 dark:text-zinc-400" role="status">
        <Spinner />
        {copy.working}
      </div>
    )
  }

  if (state === 'done') {
    return (
      <>
        <CheckCircle2 className="size-8 text-emerald-600 dark:text-emerald-400" aria-hidden />
        <h1 className="mt-4 text-2xl font-semibold tracking-tight">{copy.done}</h1>
        <p className="mt-1.5 text-sm text-zinc-500 dark:text-zinc-400">{copy.body}</p>
        {mode !== 'channel' && (
          <p className="mt-6 text-sm">
            <Link to={signedIn ? '/monitors' : '/login'} className="font-medium text-emerald-700 hover:underline dark:text-emerald-400">
              {signedIn ? 'Go to your monitors' : 'Sign in'}
            </Link>
          </p>
        )}
      </>
    )
  }

  return (
    <>
      <h1 className="text-2xl font-semibold tracking-tight">That link didn't work</h1>
      <div className="mt-6">
        <FormBanner>{error}</FormBanner>
      </div>
      <p className="mt-6 text-sm text-zinc-500 dark:text-zinc-400">
        {mode === 'verify'
          ? 'Links expire after 24 hours and work once. Sign in and use "Resend email" in the banner for a new one.'
          : mode === 'change'
            ? 'Links expire after 24 hours and work once. Start the change again from Settings → Profile.'
            : 'Links expire after 24 hours and work once. Ask the person who added you to send a new one.'}
      </p>
      <p className="mt-6 text-sm">
        <Link to={signedIn ? '/settings' : '/login'} className="font-medium text-emerald-700 hover:underline dark:text-emerald-400">
          {signedIn ? 'Go to settings' : 'Sign in'}
        </Link>
      </p>
    </>
  )
}

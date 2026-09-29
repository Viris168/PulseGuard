import { useState } from 'react'
import { MailWarning } from 'lucide-react'
import { resendVerification } from '../../api/auth'
import type { User } from '../../types/auth'

/** Shown until the account's email is verified: until then no alert emails are sent. */
export function VerifyEmailBanner({ user }: { user: User }) {
  const [state, setState] = useState<'idle' | 'sending' | 'sent' | string>('idle')

  async function resend() {
    setState('sending')
    try {
      await resendVerification()
      setState('sent')
    } catch (err) {
      setState(err instanceof Error ? err.message : 'Could not send the email')
    }
  }

  return (
    <div
      className="flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-amber-200 bg-amber-50 px-4 py-2.5 text-sm text-amber-900 sm:px-6 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-100"
      role="status"
    >
      <MailWarning className="size-4 shrink-0" aria-hidden />
      <span>
        Verify <strong className="font-medium">{user.email}</strong> to get alert emails. Check your inbox for the link.
      </span>
      {state === 'sent' ? (
        <span className="font-medium">Sent. It can take a minute.</span>
      ) : (
        <button
          type="button"
          onClick={resend}
          disabled={state === 'sending'}
          className="font-medium underline underline-offset-2 hover:no-underline disabled:opacity-60"
        >
          {state === 'sending' ? 'Sending…' : 'Resend email'}
        </button>
      )}
      {state !== 'idle' && state !== 'sending' && state !== 'sent' && <span className="text-red-700 dark:text-red-300">{state}</span>}
    </div>
  )
}

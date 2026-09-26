import type { ReactNode } from 'react'
import { Navigate, Outlet, useLocation, type Location } from 'react-router-dom'
import { Spinner } from '../components/ui/Spinner'
import { useAuth } from './authContext'

function FullPageSpinner() {
  return (
    <div className="flex min-h-dvh items-center justify-center text-zinc-400">
      <Spinner />
    </div>
  )
}

/** Signed-in pages. Anyone else goes to /login and comes back here afterwards. */
export function RequireAuth() {
  const { status } = useAuth()
  const location = useLocation()
  if (status === 'loading') return <FullPageSpinner />
  if (status === 'anonymous') return <Navigate to="/login" replace state={{ from: location }} />
  return <Outlet />
}

/**
 * Login and sign-up. Once signed in (including the moment a login succeeds), go back to the
 * page RequireAuth bounced the user from, or to the dashboard.
 */
export function RedirectIfAuthed() {
  const { status } = useAuth()
  const location = useLocation()
  if (status === 'loading') return <FullPageSpinner />
  if (status === 'authenticated') {
    const from = (location.state as { from?: Location } | null)?.from
    return <Navigate to={from ? `${from.pathname}${from.search}` : '/monitors'} replace />
  }
  return <Outlet />
}

/** "/": the landing page for visitors, the dashboard for signed-in users. */
export function HomeRoute({ landing }: { landing: ReactNode }) {
  const { status } = useAuth()
  if (status === 'loading') return <FullPageSpinner />
  if (status === 'authenticated') return <Navigate to="/monitors" replace />
  return <>{landing}</>
}

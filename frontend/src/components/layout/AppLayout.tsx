import { useCallback, useEffect, useState } from 'react'
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { Activity, AlertTriangle, CreditCard, Globe, Menu, Moon, Settings, Sun, X, type LucideIcon } from 'lucide-react'
import { cn } from '../../lib/format'
import { getBillingSummary } from '../../api/billing'
import { useAuth } from '../../auth/authContext'
import { MONITORS_CHANGED } from '../../lib/events'
import { limitsFor, nextPlan } from '../../lib/plans'
import { PLAN_LABEL, type Plan } from '../../types/auth'
import { AskAiPanel } from '../ai/AskAiPanel'
import { AskAiButton, SupportMenu } from './HeaderActions'
import { Logo } from './Logo'
import { ProfileMenu } from './ProfileMenu'

const nav: { to: string; label: string; icon: LucideIcon }[] = [
  { to: '/monitors', label: 'Monitors', icon: Activity },
  { to: '/incidents', label: 'Incidents', icon: AlertTriangle },
  { to: '/status-page', label: 'Status page', icon: Globe },
  { to: '/billing', label: 'Billing', icon: CreditCard },
  { to: '/settings', label: 'Settings', icon: Settings },
]


function useTheme() {
  const [dark, setDark] = useState(() => document.documentElement.classList.contains('dark'))
  useEffect(() => {
    document.documentElement.classList.toggle('dark', dark)
    try {
      localStorage.setItem('pg-theme', dark ? 'dark' : 'light')
    } catch {
      // Storage can be blocked; the toggle still works for this session.
    }
  }, [dark])
  return [dark, () => setDark((d) => !d)] as const
}

function SidebarContent({ plan, used, onNavigate }: { plan: Plan; used: number | null; onNavigate?: () => void }) {
  const max = limitsFor(plan).maxMonitors
  const upgrade = nextPlan(plan)
  return (
    <div className="flex h-full flex-col">
      <div className="flex h-16 shrink-0 items-center border-b border-zinc-200 px-5 dark:border-zinc-800">
        <Logo />
      </div>
      <nav className="flex-1 space-y-1 px-3 py-3" aria-label="Main">
        {nav.map(({ to, label, icon: Icon }) => (
          <NavLink
            key={to}
            to={to}
            onClick={onNavigate}
            className={({ isActive }) =>
              cn(
                'flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors',
                isActive
                  ? 'bg-zinc-100 text-zinc-900 dark:bg-zinc-800 dark:text-white'
                  : 'text-zinc-600 hover:bg-zinc-100 hover:text-zinc-900 dark:text-zinc-400 dark:hover:bg-zinc-800/60 dark:hover:text-white',
              )
            }
          >
            <Icon className="size-4.5" aria-hidden />
            {label}
          </NavLink>
        ))}
      </nav>
      <div className="m-3 rounded-lg border border-zinc-200 p-3 dark:border-zinc-800">
        <div className="flex items-center justify-between">
          <p className="text-sm font-semibold">{PLAN_LABEL[plan]} plan</p>
          {upgrade && (
            <Link to="/billing" onClick={onNavigate} className="text-xs font-medium text-emerald-700 hover:underline dark:text-emerald-400">
              Upgrade
            </Link>
          )}
        </div>
        {used !== null && (
          <>
            <p className="mt-1.5 text-xs text-zinc-500 dark:text-zinc-400">
              {max === Infinity ? `${used} monitors · unlimited` : `${used} of ${max} monitors used`}
            </p>
            {max !== Infinity && (
              <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-zinc-100 dark:bg-zinc-800">
                <div
                  className={cn('h-full rounded-full', used >= max ? 'bg-red-500' : used / max >= 0.8 ? 'bg-amber-500' : 'bg-emerald-500')}
                  style={{ width: `${Math.min(100, (used / max) * 100)}%` }}
                />
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}

export function AppLayout() {
  const [dark, toggleTheme] = useTheme()
  const [drawerOpen, setDrawerOpen] = useState(false)
  const [aiOpen, setAiOpen] = useState(false)
  // Stable, so the panel's open effect doesn't re-run on every render.
  const closeAi = useCallback(() => setAiOpen(false), [])
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const [used, setUsed] = useState<number | null>(null)

  // Cheap refresh on every navigation keeps the count right after adding or deleting monitors.
  useEffect(() => {
    const refresh = () =>
      getBillingSummary()
        .then((b) => setUsed(b.usage.monitors))
        .catch(() => setUsed(null))
    refresh()
    window.addEventListener(MONITORS_CHANGED, refresh)
    return () => window.removeEventListener(MONITORS_CHANGED, refresh)
  }, [pathname, user?.plan])
  // RequireAuth only renders this layout once signed in.
  if (!user) return null

  async function signOut() {
    await logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="min-h-dvh">
      {/* Desktop sidebar */}
      <aside className="fixed inset-y-0 left-0 hidden w-60 border-r border-zinc-200 bg-white lg:block dark:border-zinc-800 dark:bg-zinc-900">
        <SidebarContent plan={user.plan} used={used} />
      </aside>

      {/* Mobile drawer */}
      {drawerOpen && (
        <div className="fixed inset-0 z-40 lg:hidden">
          <div className="absolute inset-0 bg-zinc-950/50" onClick={() => setDrawerOpen(false)} aria-hidden />
          <aside className="relative h-full w-64 bg-white shadow-xl dark:bg-zinc-900">
            <button
              onClick={() => setDrawerOpen(false)}
              className="absolute top-4 right-3 rounded-md p-1.5 text-zinc-500 hover:bg-zinc-100 dark:hover:bg-zinc-800"
              aria-label="Close menu"
            >
              <X className="size-5" />
            </button>
            <SidebarContent plan={user.plan} used={used} onNavigate={() => setDrawerOpen(false)} />
          </aside>
        </div>
      )}

      {/* While Ask AI is open on a wide screen, the page shrinks beside it instead of hiding under it.
          Narrower screens can't spare 440px, so there the panel overlays the page. */}
      <div className={cn('transition-[padding] duration-200 lg:pl-60', aiOpen && 'xl:pr-[440px]')}>
        <header className="sticky top-0 z-30 flex h-16 items-center gap-3 border-b border-zinc-200 bg-white/80 px-4 backdrop-blur sm:px-6 dark:border-zinc-800 dark:bg-zinc-950/80">
          <button
            onClick={() => setDrawerOpen(true)}
            className="-ml-1.5 rounded-md p-1.5 text-zinc-600 hover:bg-zinc-100 lg:hidden dark:text-zinc-400 dark:hover:bg-zinc-800"
            aria-label="Open menu"
          >
            <Menu className="size-5" />
          </button>
          <div className="lg:hidden">
            <Logo />
          </div>
          <div className="ml-auto flex items-center gap-1 sm:gap-2">
            <AskAiButton open={aiOpen} onToggle={() => setAiOpen((o) => !o)} />
            <SupportMenu />
            <span className="mx-1 hidden h-5 w-px bg-zinc-200 sm:block dark:bg-zinc-800" aria-hidden />
            <button
              onClick={toggleTheme}
              className="rounded-md p-2 text-zinc-600 hover:bg-zinc-100 dark:text-zinc-400 dark:hover:bg-zinc-800"
              aria-label={dark ? 'Switch to light mode' : 'Switch to dark mode'}
            >
              {dark ? <Sun className="size-4.5" /> : <Moon className="size-4.5" />}
            </button>
            <ProfileMenu user={user} onSignOut={signOut} />
          </div>
        </header>

        <main className="mx-auto max-w-6xl px-4 py-6 sm:px-6 lg:py-8">
          <Outlet />
        </main>
      </div>

      <AskAiPanel open={aiOpen} onClose={closeAi} />
    </div>
  )
}

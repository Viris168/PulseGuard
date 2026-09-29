import { useEffect } from 'react'
import { Link, Outlet, useLocation } from 'react-router-dom'
import { cn } from '../../lib/format'
import { prefetchWhenIdle } from '../../routes/pages'
import { BellRing, ShieldCheck, Timer } from 'lucide-react'
import { PageBoundary } from '../PageBoundary'
import { Logo } from './Logo'

const preview = [
  { name: 'Production API', uptime: '100%', dot: 'bg-emerald-300', fails: [] as number[] },
  { name: 'Payments webhook', uptime: '99.65%', dot: 'bg-red-400', fails: [17, 18, 19] },
  { name: 'Marketing site', uptime: '99.98%', dot: 'bg-emerald-300', fails: [7] },
]

const points = [
  { icon: Timer, text: 'Checks every minute, from one URL to your whole stack' },
  { icon: ShieldCheck, text: 'No false alarms: incidents open after 3 failures in a row' },
  { icon: BellRing, text: 'Email and Slack alerts the moment something breaks' },
]

/** What the side panel offers: the other half of sign in / sign up, or a way back to sign in. */
function panelFor(pathname: string) {
  if (pathname === '/login') {
    return { title: 'New here?', text: 'Create a free account and add your first monitor in a minute.', cta: 'Sign up', to: '/signup' }
  }
  if (pathname === '/signup') {
    return { title: 'Welcome back!', text: 'Already watching your endpoints? Sign in to see how they are doing.', cta: 'Sign in', to: '/login' }
  }
  return { title: 'Know before your customers do.', text: 'PulseGuard watches your APIs and websites around the clock.', cta: 'Back to sign in', to: '/login' }
}

/** Compact centered card: form on the left, colored pitch panel on the right (desktop only). */
export function AuthLayout() {
  // Sign in, sign up and the dashboard are one click away: have them ready.
  useEffect(() => prefetchWhenIdle('auth'), [])
  const { pathname } = useLocation()
  const panel = panelFor(pathname)
  // On sign up the form and the panel swap sides, sliding past each other like a toggle.
  const swapped = pathname === '/signup'
  const slide = 'transition-[translate,border-radius] duration-700 ease-in-out motion-reduce:transition-none'
  return (
    <div className="flex min-h-dvh flex-col items-center justify-center bg-gradient-to-br from-zinc-100 via-white to-emerald-50 px-4 py-8 dark:from-zinc-950 dark:via-zinc-950 dark:to-emerald-950/40">
      <div className="relative w-full max-w-sm overflow-hidden rounded-2xl border border-zinc-200 bg-white shadow-xl lg:max-w-3xl dark:border-zinc-800 dark:bg-zinc-900">
        <main className={cn('px-6 py-8 sm:px-8 lg:w-1/2', slide, swapped && 'lg:translate-x-full')}>
          <Logo />
          <div key={pathname} className="mt-6 motion-safe:animate-pg-auth-in">
            <PageBoundary>
              <Outlet />
            </PageBoundary>
          </div>
        </main>

        <aside
          className={cn(
            'absolute inset-y-0 right-0 hidden w-1/2 flex-col justify-center overflow-hidden bg-gradient-to-br from-emerald-600 to-emerald-800 p-8 text-white lg:flex',
            slide,
            swapped ? '-translate-x-full rounded-r-[4rem]' : 'rounded-l-[4rem]',
          )}
        >
          <div
            className="pointer-events-none absolute inset-0 opacity-50"
            style={{ background: 'radial-gradient(60% 50% at 80% 10%, rgb(255 255 255 / 0.18), transparent 70%)' }}
            aria-hidden
          />
          <div key={pathname} className="relative text-center motion-safe:animate-pg-auth-in">
            <h2 className="text-2xl font-semibold tracking-tight">{panel.title}</h2>
            <p className="mt-2 text-sm text-emerald-50/90">{panel.text}</p>

            <div className="mt-6 rounded-xl border border-white/15 bg-black/15 px-3 py-1 text-left backdrop-blur" aria-hidden>
              {preview.map((m) => (
                <div key={m.name} className="flex items-center gap-2 border-b border-white/10 py-2 last:border-0">
                  <span className={`size-1.5 shrink-0 rounded-full ${m.dot}`} />
                  <span className="flex-1 truncate text-xs">{m.name}</span>
                  <span className="flex gap-[2px]">
                    {Array.from({ length: 20 }, (_, i) => (
                      <span key={i} className={`h-3 w-[3px] rounded-sm ${m.fails.includes(i) ? 'bg-red-400' : 'bg-emerald-200/80'}`} />
                    ))}
                  </span>
                  <span className="w-11 text-right text-[11px] text-emerald-50/80 tabular-nums">{m.uptime}</span>
                </div>
              ))}
            </div>

            <ul className="mt-5 space-y-2.5 text-left">
              {points.map(({ icon: Icon, text }) => (
                <li key={text} className="flex items-start gap-2.5 text-xs text-emerald-50">
                  <Icon className="mt-px size-3.5 shrink-0 text-emerald-200" aria-hidden />
                  {text}
                </li>
              ))}
            </ul>

            <Link
              to={panel.to}
              className="mt-7 inline-flex h-9 items-center justify-center rounded-lg border border-white/70 px-6 text-xs font-semibold tracking-wide uppercase transition hover:bg-white hover:text-emerald-700 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-white active:scale-[0.98]"
            >
              {panel.cta}
            </Link>
          </div>
        </aside>
      </div>
      <p className="mt-6 text-center text-xs text-zinc-400 dark:text-zinc-500">© {new Date().getFullYear()} PulseGuard</p>
    </div>
  )
}

import { Outlet } from 'react-router-dom'
import { BellRing, ShieldCheck, Timer } from 'lucide-react'
import { Logo } from './Logo'

const preview = [
  { name: 'Production API', status: 'Up', uptime: '100%', dot: 'bg-emerald-400', fails: [] as number[] },
  { name: 'Payments webhook', status: 'Down', uptime: '99.65%', dot: 'bg-red-400', fails: [21, 22, 23] },
  { name: 'Marketing site', status: 'Up', uptime: '99.98%', dot: 'bg-emerald-400', fails: [9] },
]

const points = [
  { icon: Timer, text: 'Checks every minute, from one URL to your whole stack' },
  { icon: ShieldCheck, text: 'No false alarms: incidents open after 3 failures in a row' },
  { icon: BellRing, text: 'Email and Slack alerts the moment something breaks' },
]

/** Split screen: form on the left, product pitch on the right (desktop only). */
export function AuthLayout() {
  return (
    <div className="grid min-h-dvh lg:grid-cols-2">
      <div className="flex flex-col px-4 py-6 sm:px-10">
        <Logo />
        <main className="flex flex-1 items-center justify-center py-10">
          <div className="w-full max-w-sm">
            <Outlet />
          </div>
        </main>
        <p className="text-center text-xs text-zinc-400 dark:text-zinc-500">© {new Date().getFullYear()} PulseGuard</p>
      </div>

      <aside className="relative hidden overflow-hidden bg-zinc-950 p-12 text-white lg:flex lg:flex-col lg:justify-center">
        <div
          className="pointer-events-none absolute inset-0 opacity-60"
          style={{ background: 'radial-gradient(60% 50% at 70% 20%, rgb(5 150 105 / 0.35), transparent 70%)' }}
          aria-hidden
        />
        <div className="relative mx-auto w-full max-w-md">
          <h2 className="text-3xl font-semibold tracking-tight">Know before your customers do.</h2>
          <p className="mt-3 text-zinc-400">
            PulseGuard watches your APIs and websites around the clock and tells you the moment one goes down.
          </p>

          <div className="mt-8 rounded-xl border border-white/10 bg-white/5 p-4 backdrop-blur" aria-hidden>
            {preview.map((m) => (
              <div key={m.name} className="flex items-center gap-3 border-b border-white/5 py-2.5 last:border-0">
                <span className={`size-2 rounded-full ${m.dot}`} />
                <span className="flex-1 truncate text-sm">{m.name}</span>
                <span className="hidden gap-[2px] sm:flex">
                  {Array.from({ length: 24 }, (_, i) => (
                    <span key={i} className={`h-4 w-1 rounded-sm ${m.fails.includes(i) ? 'bg-red-400' : 'bg-emerald-400/80'}`} />
                  ))}
                </span>
                <span className="w-14 text-right text-xs text-zinc-400 tabular-nums">{m.uptime}</span>
              </div>
            ))}
          </div>

          <ul className="mt-8 space-y-3">
            {points.map(({ icon: Icon, text }) => (
              <li key={text} className="flex items-start gap-3 text-sm text-zinc-300">
                <Icon className="mt-0.5 size-4 shrink-0 text-emerald-400" aria-hidden />
                {text}
              </li>
            ))}
          </ul>
        </div>
      </aside>
    </div>
  )
}

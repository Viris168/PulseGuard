import { Link } from 'react-router-dom'
import {
  ArrowRight,
  BarChart3,
  BellRing,
  Check,
  Globe,
  HeartPulse,
  KeyRound,
  Mail,
  ShieldCheck,
  Sparkles,
  Timer,
  type LucideIcon,
} from 'lucide-react'
import type { DisplayStatus } from '../../types/monitor'
import { cn } from '../../lib/format'
import { formatMonitorLimit, PLANS } from '../../lib/plans'
import { Logo } from '../../components/layout/Logo'
import { StatusBadge } from '../../components/ui/StatusBadge'

// ROADMAP §5 — the customer story, told as the night it happened.
const STORY: { time: string; status: DisplayStatus; title: string; body: string }[] = [
  { time: '02:13', status: 'SUSPICIOUS', title: 'Shop API returns 500', body: 'One failed check. Could be a blip, so no alert yet.' },
  { time: '02:23', status: 'DOWN', title: 'Third failure in a row', body: 'Now it’s real. PulseGuard opens an incident and emails Dara.' },
  { time: '02:38', status: 'RECOVERING', title: 'Fix deployed, check passes', body: 'Recovering. One pass isn’t proof, so it waits for another.' },
  { time: '02:43', status: 'UP', title: 'Second pass — back up', body: 'Incident resolved and a “back up” email goes out.' },
]

const FEATURES: { icon: LucideIcon; title: string; body: string }[] = [
  { icon: Timer, title: 'Checks every minute', body: 'HTTP checks with timeouts, expected status codes and response times.' },
  { icon: ShieldCheck, title: 'No false alarms', body: 'An incident opens only after 3 failures in a row. Blips stay quiet.' },
  { icon: HeartPulse, title: 'Heartbeats for cron jobs', body: 'Your job pings a secret URL. If it goes quiet, you hear about it.' },
  { icon: BellRing, title: 'Email and Slack alerts', body: 'When it breaks and when it’s back, on the channels your team reads.' },
  { icon: BarChart3, title: 'Uptime and P95 trends', body: 'See response times creep up before they turn into an outage.' },
  { icon: Globe, title: 'Public status pages', body: 'Show customers what’s up without exposing your URLs or errors.' },
  { icon: Sparkles, title: 'Ask AI', body: '“Why did the shop API go down last night?” Answered from your data.' },
  { icon: KeyRound, title: 'API keys', body: 'Manage monitors from CI, scripts or Terraform.' },
]

const preview: { name: string; status: DisplayStatus; uptime: string; fails: number[] }[] = [
  { name: 'Shop API', status: 'UP', uptime: '99.30%', fails: [15, 16] },
  { name: 'Checkout', status: 'UP', uptime: '100%', fails: [] },
  { name: 'Nightly backup', status: 'UP', uptime: '99.86%', fails: [7] },
]

export function LandingPage() {
  return (
    <div className="min-h-dvh bg-white dark:bg-zinc-950">
      <header className="sticky top-0 z-30 border-b border-zinc-200/70 bg-white/80 backdrop-blur dark:border-zinc-800 dark:bg-zinc-950/80">
        <div className="mx-auto flex h-16 max-w-6xl items-center gap-6 px-4 sm:px-6">
          <Logo />
          <nav className="hidden gap-6 text-sm text-zinc-600 md:flex dark:text-zinc-400" aria-label="Sections">
            <a href="#story" className="hover:text-zinc-900 dark:hover:text-white">How it works</a>
            <a href="#features" className="hover:text-zinc-900 dark:hover:text-white">Features</a>
            <a href="#pricing" className="hover:text-zinc-900 dark:hover:text-white">Pricing</a>
            <Link to="/status/acme" className="hover:text-zinc-900 dark:hover:text-white">Status page demo</Link>
          </nav>
          <div className="ml-auto flex items-center gap-2">
            <Link to="/login" className="rounded-lg px-3 py-2 text-sm font-medium text-zinc-700 hover:bg-zinc-100 dark:text-zinc-300 dark:hover:bg-zinc-800">
              Sign in
            </Link>
            <Link to="/signup" className="rounded-lg bg-emerald-600 px-3.5 py-2 text-sm font-medium text-white hover:bg-emerald-700">
              Get started
            </Link>
          </div>
        </div>
      </header>

      <main>
        {/* Hero */}
        <section className="relative overflow-hidden">
          <div
            className="pointer-events-none absolute inset-0"
            style={{ background: 'radial-gradient(50% 60% at 80% 10%, rgb(16 185 129 / 0.14), transparent 70%)' }}
            aria-hidden
          />
          <div className="relative mx-auto grid max-w-6xl items-center gap-12 px-4 py-16 sm:px-6 lg:grid-cols-2 lg:py-24">
            <div>
              <p className="inline-flex items-center gap-2 rounded-full border border-emerald-200 bg-emerald-50 px-3 py-1 text-xs font-medium text-emerald-800 dark:border-emerald-500/30 dark:bg-emerald-500/10 dark:text-emerald-300">
                <span className="size-1.5 rounded-full bg-emerald-500" aria-hidden />
                Uptime monitoring for APIs, sites and cron jobs
              </p>
              <h1 className="mt-5 text-4xl font-semibold tracking-tight sm:text-5xl">Know before your customers do.</h1>
              <p className="mt-5 max-w-xl text-lg text-zinc-600 dark:text-zinc-400">
                PulseGuard checks your endpoints every minute, opens an incident only when an outage is real, and alerts you the
                moment it happens — and again when it’s fixed.
              </p>
              <div className="mt-8 flex flex-col gap-3 sm:flex-row">
                <Link
                  to="/signup"
                  className="inline-flex h-11 items-center justify-center gap-2 rounded-lg bg-emerald-600 px-5 font-medium text-white hover:bg-emerald-700"
                >
                  Start free <ArrowRight className="size-4" aria-hidden />
                </Link>
                <Link
                  to="/status/acme"
                  className="inline-flex h-11 items-center justify-center rounded-lg border border-zinc-300 px-5 font-medium hover:bg-zinc-50 dark:border-zinc-700 dark:hover:bg-zinc-900"
                >
                  See a live status page
                </Link>
              </div>
              <p className="mt-3 text-sm text-zinc-500 dark:text-zinc-400">3 monitors free. No credit card.</p>
            </div>

            {/* Product preview */}
            {/* Bottom padding leaves room for the alert card to hang below the preview without covering a row. */}
            <div className="relative pb-12" aria-hidden>
              <div className="rounded-2xl border border-zinc-200 bg-white p-4 shadow-xl dark:border-zinc-800 dark:bg-zinc-900">
                <div className="mb-3 flex items-center justify-between">
                  <p className="text-sm font-semibold">Monitors</p>
                  <p className="text-xs text-zinc-500">Last 24 hours</p>
                </div>
                <ul className="divide-y divide-zinc-100 dark:divide-zinc-800">
                  {preview.map((m) => (
                    <li key={m.name} className="flex items-center gap-3 py-3">
                      <StatusBadge status={m.status} />
                      <span className="flex-1 truncate text-sm font-medium">{m.name}</span>
                      <span className="hidden gap-[2px] sm:flex">
                        {Array.from({ length: 24 }, (_, i) => (
                          <span key={i} className={cn('h-5 w-1 rounded-sm', m.fails.includes(i) ? 'bg-red-500' : 'bg-emerald-500')} />
                        ))}
                      </span>
                      <span className="w-14 text-right text-xs text-zinc-500 tabular-nums">{m.uptime}</span>
                    </li>
                  ))}
                </ul>
              </div>
              <div className="absolute bottom-0 left-6 flex max-w-xs items-start gap-3 rounded-xl border border-zinc-200 bg-white p-3 shadow-lg sm:-left-6 dark:border-zinc-700 dark:bg-zinc-800">
                <span className="flex size-8 shrink-0 items-center justify-center rounded-full bg-red-100 text-red-600 dark:bg-red-500/15 dark:text-red-400">
                  <Mail className="size-4" />
                </span>
                <div className="text-sm">
                  <p className="font-medium">Shop API is down</p>
                  <p className="text-xs text-zinc-500 dark:text-zinc-400">HTTP 500 · 3 failed checks · 02:23</p>
                </div>
              </div>
            </div>
          </div>
        </section>

        {/* Story */}
        <section id="story" className="scroll-mt-16 border-t border-zinc-200 bg-zinc-50 py-20 dark:border-zinc-800 dark:bg-zinc-900/40">
          <div className="mx-auto max-w-6xl px-4 sm:px-6">
            <div className="max-w-2xl">
              <p className="text-sm font-semibold text-emerald-700 dark:text-emerald-400">How it works</p>
              <h2 className="mt-2 text-3xl font-semibold tracking-tight">One night, 30 minutes of downtime instead of 5 hours</h2>
              <p className="mt-3 text-zinc-600 dark:text-zinc-400">
                Dara runs a shop API. She signed up, added her 3 free monitors, and went to bed. Here’s what happened next.
              </p>
            </div>
            <ol className="mt-10 grid gap-4 md:grid-cols-4">
              {STORY.map((s, i) => (
                <li key={s.time} className="relative rounded-xl border border-zinc-200 bg-white p-5 dark:border-zinc-800 dark:bg-zinc-900">
                  <div className="flex items-center justify-between">
                    <span className="font-mono text-sm font-semibold tabular-nums">{s.time}</span>
                    <StatusBadge status={s.status} />
                  </div>
                  <p className="mt-3 font-medium">{s.title}</p>
                  <p className="mt-1 text-sm text-zinc-600 dark:text-zinc-400">{s.body}</p>
                  {i < STORY.length - 1 && (
                    <ArrowRight className="absolute top-1/2 -right-3.5 z-10 hidden size-5 -translate-y-1/2 text-zinc-300 md:block dark:text-zinc-600" aria-hidden />
                  )}
                </li>
              ))}
            </ol>
            <p className="mt-6 max-w-3xl text-sm text-zinc-600 dark:text-zinc-400">
              Next morning her dashboard shows <strong className="text-zinc-900 dark:text-zinc-100">99.3% uptime</strong> — and response
              times creeping up an hour before the failure. When the shop grows to 8 monitors checked every minute, she upgrades to Pro.
            </p>
          </div>
        </section>

        {/* Features */}
        <section id="features" className="scroll-mt-16 py-20">
          <div className="mx-auto max-w-6xl px-4 sm:px-6">
            <h2 className="max-w-2xl text-3xl font-semibold tracking-tight">Everything you need to sleep through the night</h2>
            <div className="mt-10 grid gap-6 sm:grid-cols-2 lg:grid-cols-4">
              {FEATURES.map(({ icon: Icon, title, body }) => (
                <div key={title}>
                  <span className="flex size-10 items-center justify-center rounded-lg bg-emerald-50 text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-400">
                    <Icon className="size-5" aria-hidden />
                  </span>
                  <h3 className="mt-4 font-semibold">{title}</h3>
                  <p className="mt-1 text-sm text-zinc-600 dark:text-zinc-400">{body}</p>
                </div>
              ))}
            </div>
          </div>
        </section>

        {/* Pricing */}
        <section id="pricing" className="scroll-mt-16 border-t border-zinc-200 bg-zinc-50 py-20 dark:border-zinc-800 dark:bg-zinc-900/40">
          <div className="mx-auto max-w-6xl px-4 sm:px-6">
            <h2 className="text-center text-3xl font-semibold tracking-tight">Simple pricing</h2>
            <p className="mt-2 text-center text-zinc-600 dark:text-zinc-400">Start free. Upgrade when you need more monitors or faster checks.</p>
            <div className="mx-auto mt-10 grid max-w-5xl gap-4 lg:grid-cols-3">
              {PLANS.map((p) => {
                const featured = p.plan === 'PRO'
                return (
                  <div
                    key={p.plan}
                    className={cn(
                      'flex flex-col rounded-xl border bg-white p-6 dark:bg-zinc-900',
                      featured ? 'border-emerald-600 shadow-lg ring-1 ring-emerald-600 dark:border-emerald-500 dark:ring-emerald-500' : 'border-zinc-200 dark:border-zinc-800',
                    )}
                  >
                    <h3 className="font-semibold">{p.name}</h3>
                    <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">{p.tagline}</p>
                    <p className="mt-4">
                      <span className="text-4xl font-semibold">${p.price}</span>
                      <span className="text-sm text-zinc-500 dark:text-zinc-400"> / month</span>
                    </p>
                    <ul className="mt-5 flex-1 space-y-2 text-sm">
                      {[
                        `${formatMonitorLimit(p.limits.maxMonitors)} monitors`,
                        `Checks every ${p.limits.minIntervalSeconds / 60} min`,
                        `${p.limits.retentionDays}-day history`,
                        `${p.limits.channels.map((c) => (c === 'EMAIL' ? 'Email' : c === 'SLACK' ? 'Slack' : c)).join(', ')} alerts`,
                      ].map((f) => (
                        <li key={f} className="flex items-center gap-2">
                          <Check className="size-4 shrink-0 text-emerald-600 dark:text-emerald-400" aria-hidden />
                          {f}
                        </li>
                      ))}
                    </ul>
                    <Link
                      to="/signup"
                      className={cn(
                        'mt-6 inline-flex h-10 items-center justify-center rounded-lg text-sm font-medium',
                        featured
                          ? 'bg-emerald-600 text-white hover:bg-emerald-700'
                          : 'border border-zinc-300 hover:bg-zinc-50 dark:border-zinc-700 dark:hover:bg-zinc-800',
                      )}
                    >
                      {p.price === 0 ? 'Start free' : `Start with ${p.name}`}
                    </Link>
                  </div>
                )
              })}
            </div>
          </div>
        </section>

        {/* Final CTA */}
        <section className="py-20">
          <div className="mx-auto max-w-3xl px-4 text-center sm:px-6">
            <h2 className="text-3xl font-semibold tracking-tight">Your first monitor takes a minute.</h2>
            <p className="mt-3 text-zinc-600 dark:text-zinc-400">Paste a URL. We’ll start checking it right away.</p>
            <Link
              to="/signup"
              className="mt-8 inline-flex h-11 items-center gap-2 rounded-lg bg-emerald-600 px-6 font-medium text-white hover:bg-emerald-700"
            >
              Create your account <ArrowRight className="size-4" aria-hidden />
            </Link>
          </div>
        </section>
      </main>

      <footer className="border-t border-zinc-200 py-8 dark:border-zinc-800">
        <div className="mx-auto flex max-w-6xl flex-col items-center justify-between gap-3 px-4 text-sm text-zinc-500 sm:flex-row sm:px-6 dark:text-zinc-400">
          <Logo />
          <p>© {new Date().getFullYear()} PulseGuard</p>
        </div>
      </footer>
    </div>
  )
}

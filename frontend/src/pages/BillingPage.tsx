import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { AlertTriangle, Check, CheckCircle2, CreditCard, ExternalLink, Info, Minus, X } from 'lucide-react'
import { getBillingSummary, mockPortalChangePlan, mockPortalResume, openPortal, startCheckout } from '../api/billing'
import { useAuth } from '../auth/authContext'
import type { Plan } from '../types/auth'
import type { BillingSummary } from '../types/billing'
import type { ChannelType } from '../types/incident'
import { cn } from '../lib/format'
import { formatMonitorLimit, PLAN_ORDER, PLANS, planInfo, type PlanInfo } from '../lib/plans'
import { PageHeader } from '../components/layout/PageHeader'
import { UsageMeter } from '../components/billing/UsageMeter'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { Modal } from '../components/ui/Modal'
import { Spinner } from '../components/ui/Spinner'

const CHANNEL_NAME: Record<ChannelType, string> = {
  EMAIL: 'Email',
  SLACK: 'Slack',
  SMS: 'SMS',
  TELEGRAM: 'Telegram',
  WEBHOOK: 'Webhook',
}
const ALL_CHANNELS: ChannelType[] = ['EMAIL', 'SLACK', 'SMS']

const longDate = new Intl.DateTimeFormat(undefined, { month: 'long', day: 'numeric', year: 'numeric' })
const formatLongDate = (iso: string) => longDate.format(new Date(iso))

const everyLabel = (seconds: number) => `Every ${seconds / 60} min`

export function BillingPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const [summary, setSummary] = useState<BillingSummary | null>(null)
  const [pending, setPending] = useState<PlanInfo | null>(null)
  const [working, setWorking] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)
  const [portalLoading, setPortalLoading] = useState(false)

  const load = useCallback(() => getBillingSummary().then(setSummary), [])

  useEffect(() => {
    load()
    // The plan lives on the user; reload when it changes (upgrade, downgrade, other tab).
  }, [load, user?.plan])

  // Only trust the success return if it matches the plan we actually have (stale links, other accounts).
  const checkoutDone = params.get('checkout') === 'success' && !!summary && params.get('plan') === summary.plan
  const notice = checkoutDone ? 'checkout' : params.get('portal') === 'mock' ? 'portal' : null
  const dismissNotice = () => setParams({}, { replace: true })

  async function confirmChange() {
    if (!pending || !summary) return
    setWorking(true)
    setActionError(null)
    try {
      if (PLAN_ORDER[pending.plan] > PLAN_ORDER[summary.plan]) {
        // Real flow: this URL is Stripe Checkout; the webhook upgrades the plan after payment.
        const { url } = await startCheckout(pending.plan as Exclude<Plan, 'FREE'>)
        if (url.startsWith('/')) navigate(url, { replace: true })
        else window.location.assign(url)
      } else {
        // Real flow: downgrades happen in the Stripe Customer Portal (openPortal).
        await mockPortalChangePlan(pending.plan)
      }
      setPending(null)
      await load()
    } catch (e) {
      setActionError(e instanceof Error ? e.message : 'Something went wrong')
    } finally {
      setWorking(false)
    }
  }

  async function manageBilling() {
    setPortalLoading(true)
    try {
      const { url } = await openPortal()
      if (url.startsWith('/')) navigate(url, { replace: true })
      else window.location.assign(url)
    } finally {
      setPortalLoading(false)
    }
  }

  async function resume() {
    setWorking(true)
    try {
      await mockPortalResume()
      await load()
    } finally {
      setWorking(false)
    }
  }

  if (!summary || !user) {
    return (
      <>
        <PageHeader title="Billing" />
        <div className="flex justify-center py-24 text-zinc-400">
          <Spinner />
        </div>
      </>
    )
  }

  const current = planInfo(summary.plan)
  const paid = summary.plan !== 'FREE'

  return (
    <>
      <PageHeader
        title="Billing"
        description="Your plan, what you're using, and what each plan includes."
        actions={
          paid && (
            <Button variant="secondary" onClick={manageBilling} loading={portalLoading}>
              {!portalLoading && <CreditCard className="size-4" aria-hidden />}
              Manage billing
              <ExternalLink className="size-3.5 opacity-60" aria-hidden />
            </Button>
          )
        }
      />

      <div className="space-y-3">
        {notice === 'checkout' && (
          <Notice tone="green" icon={CheckCircle2} onDismiss={dismissNotice} title={`You're on ${current.name} now`}>
            Thanks for upgrading. Your new limits apply right away.
          </Notice>
        )}
        {notice === 'portal' && (
          <Notice tone="sky" icon={Info} onDismiss={dismissNotice} title="Stripe Customer Portal">
            In production this opens Stripe's billing portal, where you update your card, download invoices and change
            or cancel your plan. While the app runs on mock data, use the plan buttons below instead.
          </Notice>
        )}
        {summary.cancelAtPeriodEnd && summary.currentPeriodEnd && (
          <Notice
            tone="amber"
            icon={AlertTriangle}
            title={`Your ${current.name} plan ends on ${formatLongDate(summary.currentPeriodEnd)}`}
            action={
              <Button size="sm" variant="secondary" onClick={resume} loading={working}>
                Keep {current.name}
              </Button>
            }
          >
            You'll move to Free after that.
            {summary.usage.monitors > planInfo('FREE').limits.maxMonitors &&
              ` Free allows ${planInfo('FREE').limits.maxMonitors} monitors and you have ${summary.usage.monitors}.`}
          </Notice>
        )}
        {summary.status === 'past_due' && (
          <Notice tone="red" icon={AlertTriangle} title="Your last payment failed" action={<Button size="sm" onClick={manageBilling}>Update card</Button>}>
            Update your payment method to keep your {current.name} features.
          </Notice>
        )}
      </div>

      <div className={cn('grid gap-4 lg:grid-cols-3', (notice || summary.cancelAtPeriodEnd || summary.status === 'past_due') && 'mt-6')}>
        <Card className="p-5">
          <p className="text-sm font-medium text-zinc-500 dark:text-zinc-400">Current plan</p>
          <div className="mt-2 flex items-baseline gap-2">
            <p className="text-3xl font-semibold">{current.name}</p>
            <p className="text-sm text-zinc-500 dark:text-zinc-400">{current.price ? `$${current.price}/month` : 'Free forever'}</p>
          </div>
          <p className="mt-3 text-sm text-zinc-600 dark:text-zinc-400">
            {!paid
              ? 'No card on file.'
              : summary.currentPeriodEnd
                ? `${summary.cancelAtPeriodEnd ? 'Ends' : 'Renews'} on ${formatLongDate(summary.currentPeriodEnd)}`
                : 'Active'}
          </p>
        </Card>

        <Card className="p-5 lg:col-span-2">
          <p className="text-sm font-medium text-zinc-500 dark:text-zinc-400">Usage</p>
          <div className="mt-3">
            <UsageMeter label="Monitors" used={summary.usage.monitors} limit={current.limits.maxMonitors} />
          </div>
          <dl className="mt-5 grid gap-4 border-t border-zinc-200 pt-4 text-sm sm:grid-cols-3 dark:border-zinc-800">
            <LimitItem label="Fastest checks" value={everyLabel(current.limits.minIntervalSeconds)} />
            <LimitItem label="History kept" value={`${current.limits.retentionDays} days`} />
            <LimitItem label="Alert channels" value={current.limits.channels.map((c) => CHANNEL_NAME[c]).join(', ')} />
          </dl>
        </Card>
      </div>

      <h2 className="mt-10 mb-4 text-lg font-semibold">Plans</h2>
      <div className="grid gap-4 lg:grid-cols-3">
        {PLANS.map((p) => (
          <PlanCard
            key={p.plan}
            info={p}
            summary={summary}
            onChoose={() => {
              setActionError(null)
              setPending(p)
            }}
          />
        ))}
      </div>
      <p className="mt-4 text-xs text-zinc-500 dark:text-zinc-400">Prices in USD, billed monthly. Payments are handled securely by Stripe.</p>

      <ChangePlanDialog
        target={pending}
        summary={summary}
        working={working}
        error={actionError}
        onCancel={() => setPending(null)}
        onConfirm={confirmChange}
      />
    </>
  )
}

function LimitItem({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs text-zinc-500 dark:text-zinc-400">{label}</dt>
      <dd className="mt-0.5 font-medium">{value}</dd>
    </div>
  )
}

interface NoticeProps {
  tone: 'green' | 'sky' | 'amber' | 'red'
  icon: typeof Info
  title: string
  children?: ReactNode
  action?: ReactNode
  onDismiss?: () => void
}

function Notice({ tone, icon: Icon, title, children, action, onDismiss }: NoticeProps) {
  const tones = {
    green: 'border-emerald-200 bg-emerald-50 text-emerald-900 dark:border-emerald-500/30 dark:bg-emerald-500/10 dark:text-emerald-100',
    sky: 'border-sky-200 bg-sky-50 text-sky-900 dark:border-sky-500/30 dark:bg-sky-500/10 dark:text-sky-100',
    amber: 'border-amber-200 bg-amber-50 text-amber-900 dark:border-amber-500/30 dark:bg-amber-500/10 dark:text-amber-100',
    red: 'border-red-200 bg-red-50 text-red-900 dark:border-red-500/30 dark:bg-red-500/10 dark:text-red-100',
  }
  return (
    <div className={cn('flex gap-3 rounded-xl border p-4', tones[tone])} role="status">
      <Icon className="mt-0.5 size-5 shrink-0" aria-hidden />
      <div className="min-w-0 flex-1">
        <p className="font-semibold">{title}</p>
        {children && <p className="mt-0.5 text-sm opacity-90">{children}</p>}
      </div>
      {action && <div className="shrink-0 self-center">{action}</div>}
      {onDismiss && (
        <button onClick={onDismiss} className="-m-1 shrink-0 self-start rounded-md p-1 opacity-60 hover:opacity-100" aria-label="Dismiss">
          <X className="size-4" />
        </button>
      )}
    </div>
  )
}

function PlanCard({ info, summary, onChoose }: { info: PlanInfo; summary: BillingSummary; onChoose: () => void }) {
  const isCurrent = info.plan === summary.plan
  const higher = PLAN_ORDER[info.plan] > PLAN_ORDER[summary.plan]
  // Downgrading to Free is already scheduled.
  const scheduled = info.plan === 'FREE' && summary.cancelAtPeriodEnd
  const featured = info.plan === 'PRO'

  const features: { text: string; included: boolean }[] = [
    { text: `${formatMonitorLimit(info.limits.maxMonitors)} monitors`, included: true },
    { text: `Checks ${everyLabel(info.limits.minIntervalSeconds).toLowerCase()}`, included: true },
    { text: `${info.limits.retentionDays}-day history`, included: true },
    ...ALL_CHANNELS.map((c) => ({ text: `${CHANNEL_NAME[c]} alerts`, included: info.limits.channels.includes(c) })),
  ]

  return (
    <Card
      className={cn(
        'relative flex flex-col p-5',
        isCurrent && 'ring-2 ring-emerald-600 dark:ring-emerald-500',
      )}
    >
      {featured && !isCurrent && (
        <span className="absolute -top-2.5 left-5 rounded-full bg-zinc-900 px-2 py-0.5 text-xs font-medium text-white dark:bg-white dark:text-zinc-900">
          Most popular
        </span>
      )}
      <div className="flex items-center justify-between">
        <h3 className="text-base font-semibold">{info.name}</h3>
        {isCurrent && (
          <span className="rounded-full bg-emerald-50 px-2 py-0.5 text-xs font-medium text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-400">
            Current plan
          </span>
        )}
      </div>
      <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">{info.tagline}</p>
      <p className="mt-4">
        <span className="text-4xl font-semibold">${info.price}</span>
        <span className="text-sm text-zinc-500 dark:text-zinc-400"> / month</span>
      </p>

      <ul className="mt-5 flex-1 space-y-2 text-sm">
        {features.map((f) => (
          <li key={f.text} className={cn('flex items-center gap-2', !f.included && 'text-zinc-400 dark:text-zinc-500')}>
            {f.included ? (
              <Check className="size-4 shrink-0 text-emerald-600 dark:text-emerald-400" aria-hidden />
            ) : (
              <Minus className="size-4 shrink-0" aria-hidden />
            )}
            <span>
              {f.text}
              {!f.included && <span className="sr-only"> (not included)</span>}
            </span>
          </li>
        ))}
      </ul>

      <div className="mt-6">
        {isCurrent ? (
          <Button variant="secondary" disabled className="w-full">
            {summary.cancelAtPeriodEnd ? 'Ends at period end' : 'Your plan'}
          </Button>
        ) : scheduled ? (
          <Button variant="secondary" disabled className="w-full">
            Starts at period end
          </Button>
        ) : (
          <Button variant={higher ? 'primary' : 'secondary'} className="w-full" onClick={onChoose}>
            {higher ? `Upgrade to ${info.name}` : `Switch to ${info.name}`}
          </Button>
        )}
      </div>
    </Card>
  )
}

interface DialogProps {
  target: PlanInfo | null
  summary: BillingSummary
  working: boolean
  error: string | null
  onCancel: () => void
  onConfirm: () => void
}

function ChangePlanDialog({ target, summary, working, error, onCancel, onConfirm }: DialogProps) {
  if (!target) return null
  const upgrade = PLAN_ORDER[target.plan] > PLAN_ORDER[summary.plan]
  const current = planInfo(summary.plan)
  const over = summary.usage.monitors - target.limits.maxMonitors
  const lost = current.limits.channels.filter((c) => !target.limits.channels.includes(c))

  return (
    <Modal
      open
      onClose={() => !working && onCancel()}
      title={upgrade ? `Upgrade to ${target.name}` : `Switch to ${target.name}`}
      footer={
        <>
          <Button variant="secondary" onClick={onCancel} disabled={working}>
            Cancel
          </Button>
          <Button variant={upgrade ? 'primary' : 'danger'} onClick={onConfirm} loading={working}>
            {upgrade ? `Continue to payment` : `Switch to ${target.name}`}
          </Button>
        </>
      }
    >
      {upgrade ? (
        <>
          <p>
            You'll pay <strong className="text-zinc-900 dark:text-zinc-100">${target.price}/month</strong> and get{' '}
            {formatMonitorLimit(target.limits.maxMonitors).toLowerCase()} monitors, {target.limits.retentionDays}-day history and{' '}
            {target.limits.channels.map((c) => CHANNEL_NAME[c]).join(', ')} alerts.
          </p>
          <p className="mt-3 rounded-lg bg-zinc-100 px-3 py-2 text-xs dark:bg-zinc-800">
            In production this opens Stripe Checkout. On mock data, continuing simulates a successful payment.
          </p>
        </>
      ) : (
        <>
          <p>
            {target.plan === 'FREE'
              ? `Your ${current.name} features stay until the end of this billing period${summary.currentPeriodEnd ? ` (${formatLongDate(summary.currentPeriodEnd)})` : ''}. Then you'll move to Free.`
              : `The change applies right away. Stripe credits the unused part of this period to your next invoice.`}
          </p>
          <ul className="mt-3 list-disc space-y-1 pl-5">
            <li>History drops to {target.limits.retentionDays} days</li>
            {target.limits.minIntervalSeconds > current.limits.minIntervalSeconds && (
              <li>Fastest checks become {everyLabel(target.limits.minIntervalSeconds).toLowerCase()}</li>
            )}
            {lost.length > 0 && <li>{lost.map((c) => CHANNEL_NAME[c]).join(' and ')} alerts stop</li>}
          </ul>
          {over > 0 && (
            <p className="mt-3 rounded-lg bg-amber-50 px-3 py-2 text-amber-900 dark:bg-amber-500/10 dark:text-amber-200">
              You have {summary.usage.monitors} monitors and {target.name} allows {target.limits.maxMonitors}. You'll need to
              delete {over} before you can add new ones.
            </p>
          )}
          <p className="mt-3 rounded-lg bg-zinc-100 px-3 py-2 text-xs dark:bg-zinc-800">
            In production this happens in the Stripe Customer Portal.
          </p>
        </>
      )}
      {error && (
        <p className="mt-3 text-red-600 dark:text-red-400" role="alert">
          {error}
        </p>
      )}
    </Modal>
  )
}

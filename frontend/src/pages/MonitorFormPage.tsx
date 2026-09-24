import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, AlertCircle, Globe, HeartPulse, type LucideIcon } from 'lucide-react'
import { getBillingSummary } from '../api/billing'
import { ApiError, loadErrorMessage } from '../api/errors'
import { createMonitor, getMonitor, updateMonitor } from '../api/monitors'
import { useAuth } from '../auth/authContext'
import { cn } from '../lib/format'
import { limitsFor, nextPlan, planInfo } from '../lib/plans'
import { UpgradePrompt } from '../components/billing/UpgradePrompt'
import type { HttpMethod, MonitorRequest, MonitorType } from '../types/monitor'
import { PageHeader } from '../components/layout/PageHeader'
import { Button, ButtonLink } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { EmptyState } from '../components/ui/EmptyState'
import { Field, Input, Select } from '../components/ui/Field'
import { Spinner } from '../components/ui/Spinner'

const METHODS: HttpMethod[] = ['GET', 'POST', 'PUT', 'HEAD']

// Backend minimum is 60s; plan limits narrow this per tier.
const INTERVALS = [
  { value: 60, label: 'Every 1 minute' },
  { value: 300, label: 'Every 5 minutes' },
  { value: 600, label: 'Every 10 minutes' },
  { value: 900, label: 'Every 15 minutes' },
  { value: 1800, label: 'Every 30 minutes' },
  { value: 3600, label: 'Every hour' },
]

// A heartbeat's period is how often the user's job runs, so it goes much longer.
const HEARTBEAT_PERIODS = [
  { value: 300, label: 'Every 5 minutes' },
  { value: 900, label: 'Every 15 minutes' },
  { value: 3600, label: 'Every hour' },
  { value: 6 * 3600, label: 'Every 6 hours' },
  { value: 86_400, label: 'Every day' },
  { value: 7 * 86_400, label: 'Every week' },
]

const GRACE_PERIODS = [
  { value: 60, label: '1 minute' },
  { value: 300, label: '5 minutes' },
  { value: 600, label: '10 minutes' },
  { value: 1800, label: '30 minutes' },
  { value: 3600, label: '1 hour' },
  { value: 6 * 3600, label: '6 hours' },
]

const TYPES: { type: MonitorType; title: string; body: string; icon: LucideIcon }[] = [
  { type: 'HTTP', title: 'Website or API', body: 'PulseGuard calls your URL on a schedule.', icon: Globe },
  { type: 'HEARTBEAT', title: 'Heartbeat', body: 'Your cron job or worker pings PulseGuard. Silence means down.', icon: HeartPulse },
]

/** Form state is all strings so inputs stay controlled while the user is mid-edit. */
interface FormState {
  type: MonitorType
  name: string
  url: string
  method: HttpMethod
  expectedStatus: string
  intervalSeconds: string
  timeoutSeconds: string
  graceSeconds: string
}

type Errors = Partial<Record<keyof FormState, string>>

const empty: FormState = {
  type: 'HTTP',
  name: '',
  url: 'https://',
  method: 'GET',
  expectedStatus: '200',
  intervalSeconds: '300',
  timeoutSeconds: '10',
  graceSeconds: '300',
}

/** Same rules as MonitorRequest's Bean Validation, so the user sees errors before the round trip. */
function validate(f: FormState): Errors {
  const e: Errors = {}
  if (!f.name.trim()) e.name = 'Name is required'
  else if (f.name.length > 100) e.name = 'Name must be 100 characters or fewer'
  if (f.type === 'HEARTBEAT') return e

  if (!/^https?:\/\/.+/.test(f.url.trim())) e.url = 'URL must start with http:// or https://'
  else if (f.url.length > 2048) e.url = 'URL is too long'

  const status = Number(f.expectedStatus)
  if (!Number.isInteger(status) || status < 100 || status > 599) e.expectedStatus = 'Enter a status code from 100 to 599'

  const timeout = Number(f.timeoutSeconds)
  if (!Number.isFinite(timeout) || timeout < 1 || timeout > 30) e.timeoutSeconds = 'Timeout must be 1–30 seconds'

  return e
}

function toRequest(f: FormState): MonitorRequest {
  return {
    type: f.type,
    name: f.name.trim(),
    url: f.type === 'HTTP' ? f.url.trim() : '',
    method: f.method,
    expectedStatus: Number(f.expectedStatus),
    intervalSeconds: Number(f.intervalSeconds),
    timeoutMs: f.type === 'HTTP' ? Math.round(Number(f.timeoutSeconds) * 1000) : 0,
    ...(f.type === 'HEARTBEAT' ? { graceSeconds: Number(f.graceSeconds) } : {}),
  }
}

export function MonitorFormPage() {
  const { id } = useParams()
  const editing = id !== undefined
  const navigate = useNavigate()

  const [form, setForm] = useState<FormState>(empty)
  const [errors, setErrors] = useState<Errors>({})
  const [touched, setTouched] = useState(false)
  const [loading, setLoading] = useState(editing)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [submitError, setSubmitError] = useState<{ message: string; planLimit: boolean } | null>(null)
  const { user } = useAuth()
  const plan = user?.plan ?? 'FREE'
  const minInterval = limitsFor(plan).minIntervalSeconds
  const upgradeTo = nextPlan(plan)
  // New monitors only: are we already at the plan's monitor limit?
  const [atLimit, setAtLimit] = useState<boolean | null>(editing ? false : null)

  useEffect(() => {
    if (editing) return
    getBillingSummary()
      .then((b) => setAtLimit(b.usage.monitors >= limitsFor(b.plan).maxMonitors))
      .catch(() => setAtLimit(false)) // the create call still enforces it
  }, [editing])

  useEffect(() => {
    if (!editing) return
    getMonitor(Number(id))
      .then((m) =>
        setForm({
          type: m.type,
          name: m.name,
          url: m.url,
          method: m.method,
          expectedStatus: String(m.expectedStatus),
          intervalSeconds: String(m.intervalSeconds),
          timeoutSeconds: String(m.timeoutMs / 1000),
          graceSeconds: String(m.graceSeconds ?? 300),
        }),
      )
      .catch((e: unknown) => setLoadError(loadErrorMessage(e, 'monitor')))
      .finally(() => setLoading(false))
  }, [editing, id])

  function set<K extends keyof FormState>(key: K, value: FormState[K]) {
    const next = { ...form, [key]: value }
    setForm(next)
    // After the first submit attempt, re-validate live so errors clear as they're fixed.
    if (touched) setErrors(validate(next))
  }

  function chooseType(type: MonitorType) {
    // Each type has its own sensible default period.
    const next = { ...form, type, intervalSeconds: type === 'HEARTBEAT' ? '3600' : '300' }
    setForm(next)
    if (touched) setErrors(validate(next))
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setTouched(true)
    const found = validate(form)
    setErrors(found)
    if (Object.keys(found).length) return

    setSaving(true)
    setSubmitError(null)
    try {
      if (editing) {
        await updateMonitor(Number(id), toRequest(form))
        navigate(`/monitors/${id}`)
      } else {
        const created = await createMonitor(toRequest(form))
        // Heartbeats are useless until the job knows the ping URL, so land on the setup view.
        navigate(created.type === 'HEARTBEAT' ? `/monitors/${created.id}` : '/monitors')
      }
    } catch (err) {
      setSubmitError({
        message: err instanceof Error ? err.message : 'Something went wrong',
        planLimit: err instanceof ApiError && err.status === 403,
      })
      setSaving(false)
    }
  }

  const heartbeat = form.type === 'HEARTBEAT'
  const periods = heartbeat ? HEARTBEAT_PERIODS : INTERVALS
  const intervalKnown = periods.some((i) => String(i.value) === form.intervalSeconds)

  return (
    <div className="mx-auto max-w-2xl">
      <Link
        to="/monitors"
        className="mb-4 inline-flex items-center gap-1.5 text-sm text-zinc-500 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-white"
      >
        <ArrowLeft className="size-4" aria-hidden />
        Back to monitors
      </Link>

      <PageHeader
        title={editing ? 'Edit monitor' : 'Add monitor'}
        description={
          editing
            ? 'Changes apply from the next check.'
            : heartbeat
              ? "We'll give you a URL to call when your job finishes."
              : 'Paste a URL and PulseGuard starts checking it right away.'
        }
      />

      {loading || atLimit === null ? (
        <div className="flex justify-center py-20 text-zinc-400">
          <Spinner />
        </div>
      ) : atLimit ? (
        <UpgradePrompt
          title={`You've used all ${limitsFor(plan).maxMonitors} monitors on the ${planInfo(plan).name} plan`}
          cta={upgradeTo ? `Upgrade to ${upgradeTo.name}` : 'See plans'}
        >
          Delete a monitor you no longer need, or upgrade to add more.
        </UpgradePrompt>
      ) : loadError ? (
        <Card>
          <EmptyState
            icon={AlertCircle}
            title="Monitor not found"
            description={loadError}
            action={<ButtonLink to="/monitors" variant="secondary">Back to monitors</ButtonLink>}
          />
        </Card>
      ) : (
        <Card>
          <form onSubmit={onSubmit} noValidate>
            <div className="space-y-5 p-5 sm:p-6">
              <div role="radiogroup" aria-label="Monitor type" className="grid gap-3 sm:grid-cols-2">
                {TYPES.map(({ type, title, body, icon: Icon }) => (
                  <button
                    key={type}
                    type="button"
                    role="radio"
                    aria-checked={form.type === type}
                    // A monitor's type is fixed once created.
                    disabled={editing && form.type !== type}
                    onClick={() => chooseType(type)}
                    className={cn(
                      'flex gap-3 rounded-lg border p-3.5 text-left transition-colors disabled:cursor-not-allowed disabled:opacity-40',
                      form.type === type
                        ? 'border-emerald-600 bg-emerald-50 dark:border-emerald-500 dark:bg-emerald-500/10'
                        : 'border-zinc-200 enabled:hover:bg-zinc-50 dark:border-zinc-800 dark:enabled:hover:bg-zinc-800/50',
                    )}
                  >
                    <Icon
                      className={cn('mt-0.5 size-5 shrink-0', form.type === type ? 'text-emerald-600 dark:text-emerald-400' : 'text-zinc-400')}
                      aria-hidden
                    />
                    <span>
                      <span className="block text-sm font-medium">{title}</span>
                      <span className="mt-0.5 block text-xs text-zinc-500 dark:text-zinc-400">{body}</span>
                    </span>
                  </button>
                ))}
              </div>

              <Field id="name" label="Name" error={errors.name}>
                <Input
                  id="name"
                  value={form.name}
                  onChange={(e) => set('name', e.target.value)}
                  placeholder={heartbeat ? 'Nightly backup' : 'Production API'}
                  maxLength={100}
                  autoFocus
                  error={errors.name}
                />
              </Field>

              {!heartbeat && (
                <div className="grid gap-5 sm:grid-cols-[7rem_1fr]">
                  <Field id="method" label="Method">
                    <Select id="method" value={form.method} onChange={(e) => set('method', e.target.value as HttpMethod)}>
                      {METHODS.map((m) => (
                        <option key={m}>{m}</option>
                      ))}
                    </Select>
                  </Field>
                  <Field id="url" label="URL" error={errors.url}>
                    <Input
                      id="url"
                      type="url"
                      inputMode="url"
                      value={form.url}
                      onChange={(e) => set('url', e.target.value)}
                      placeholder="https://api.example.com/health"
                      className="font-mono"
                      error={errors.url}
                    />
                  </Field>
                </div>
              )}
            </div>

            <div className="space-y-5 border-t border-zinc-200 p-5 sm:p-6 dark:border-zinc-800">
              <div>
                <h2 className="text-sm font-semibold">{heartbeat ? 'Schedule' : 'Check settings'}</h2>
                <p className="mt-0.5 text-xs text-zinc-500 dark:text-zinc-400">
                  {heartbeat
                    ? "If a ping hasn't arrived by the expected time plus the grace period, the monitor goes down and you're alerted."
                    : "An incident opens after 3 failed checks in a row, so one blip won't wake you up."}
                </p>
              </div>

              <div className={cn('grid gap-5', heartbeat ? 'sm:grid-cols-2' : 'sm:grid-cols-3')}>
                <Field
                  id="interval"
                  label={heartbeat ? 'Expect a ping' : 'Check interval'}
                  hint={!heartbeat && minInterval > 60 && upgradeTo ? `Every minute on ${upgradeTo.name}` : undefined}
                >
                  <Select id="interval" value={form.intervalSeconds} onChange={(e) => set('intervalSeconds', e.target.value)}>
                    {!intervalKnown && <option value={form.intervalSeconds}>Every {form.intervalSeconds}s</option>}
                    {periods.map((i) => {
                      const locked = !heartbeat && i.value < minInterval
                      return (
                        <option key={i.value} value={i.value} disabled={locked}>
                          {i.label}
                          {locked && upgradeTo ? ` (${upgradeTo.name})` : ''}
                        </option>
                      )
                    })}
                  </Select>
                </Field>

                {heartbeat ? (
                  <Field id="grace" label="Grace period" hint="Allow for jobs that run long.">
                    <Select id="grace" value={form.graceSeconds} onChange={(e) => set('graceSeconds', e.target.value)}>
                      {GRACE_PERIODS.map((g) => (
                        <option key={g.value} value={g.value}>
                          {g.label}
                        </option>
                      ))}
                    </Select>
                  </Field>
                ) : (
                  <>
                    <Field id="timeout" label="Timeout" hint="1–30 seconds" error={errors.timeoutSeconds}>
                      <div className="relative">
                        <Input
                          id="timeout"
                          type="number"
                          min={1}
                          max={30}
                          step={1}
                          value={form.timeoutSeconds}
                          onChange={(e) => set('timeoutSeconds', e.target.value)}
                          className="pr-10"
                          error={errors.timeoutSeconds}
                        />
                        <span className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-sm text-zinc-400">sec</span>
                      </div>
                    </Field>

                    <Field id="status" label="Expected status" hint="Anything else counts as down" error={errors.expectedStatus}>
                      <Input
                        id="status"
                        type="number"
                        min={100}
                        max={599}
                        value={form.expectedStatus}
                        onChange={(e) => set('expectedStatus', e.target.value)}
                        error={errors.expectedStatus}
                      />
                    </Field>
                  </>
                )}
              </div>
            </div>

            <div className="flex flex-col-reverse gap-3 rounded-b-xl border-t border-zinc-200 bg-zinc-50 px-5 py-4 sm:flex-row sm:items-center sm:justify-end sm:px-6 dark:border-zinc-800 dark:bg-zinc-900/60">
              {submitError && (
                <p className="text-sm text-red-600 sm:mr-auto dark:text-red-400" role="alert">
                  {submitError.message}
                  {submitError.planLimit && (
                    <>
                      {' '}
                      <Link to="/billing" className="font-medium underline underline-offset-2">
                        See plans
                      </Link>
                    </>
                  )}
                </p>
              )}
              <ButtonLink to={editing ? `/monitors/${id}` : '/monitors'} variant="secondary">
                Cancel
              </ButtonLink>
              <Button type="submit" loading={saving}>
                {editing ? 'Save changes' : heartbeat ? 'Create heartbeat' : 'Create monitor'}
              </Button>
            </div>
          </form>
        </Card>
      )}
    </div>
  )
}

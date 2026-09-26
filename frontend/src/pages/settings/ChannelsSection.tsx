import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { Hash, Lock, Mail, Send, Smartphone, Trash2, Webhook, type LucideIcon } from 'lucide-react'
import { CHANNEL_RULES, createChannel, deleteChannel, listChannels, sendTestAlert, setChannelEnabled } from '../../api/channels'
import { ApiError } from '../../api/errors'
import { useAuth } from '../../auth/authContext'
import type { ChannelType, NotificationChannel } from '../../types/incident'
import { cn } from '../../lib/format'
import { limitsFor, PLANS } from '../../lib/plans'
import { UpgradePrompt } from '../../components/billing/UpgradePrompt'
import { Button } from '../../components/ui/Button'
import { Field, Input } from '../../components/ui/Field'
import { Modal } from '../../components/ui/Modal'
import { Spinner } from '../../components/ui/Spinner'
import { Switch } from '../../components/ui/Switch'
import { readApiError } from '../auth/authForm'
import { SettingsCard } from './SettingsCard'

// The types any plan offers (PlanLimits never grants Telegram or Webhook yet).
const TYPES: { type: ChannelType; label: string; icon: LucideIcon; placeholder: string; hint: string }[] = [
  { type: 'EMAIL', label: 'Email', icon: Mail, placeholder: 'oncall@company.com', hint: 'Any address — a person or a team list.' },
  {
    type: 'SLACK',
    label: 'Slack',
    icon: Hash,
    placeholder: 'https://hooks.slack.com/services/…',
    hint: 'Create an incoming webhook in Slack and paste its URL.',
  },
  { type: 'SMS', label: 'SMS', icon: Smartphone, placeholder: '+85512345678', hint: 'International format with country code.' },
]

const ICON: Record<ChannelType, LucideIcon> = { EMAIL: Mail, SLACK: Hash, SMS: Smartphone, TELEGRAM: Send, WEBHOOK: Webhook }
const LABEL: Record<ChannelType, string> = { EMAIL: 'Email', SLACK: 'Slack', SMS: 'SMS', TELEGRAM: 'Telegram', WEBHOOK: 'Webhook' }

/** Webhook URLs are credentials: never show them in full once saved. */
function displayTarget(c: NotificationChannel): string {
  if (c.type === 'SLACK' || c.type === 'WEBHOOK') {
    try {
      const u = new URL(c.target)
      return `${u.host}/…${c.target.slice(-4)}`
    } catch {
      return `…${c.target.slice(-4)}`
    }
  }
  return c.target
}

/** Cheapest plan that includes a channel type. */
const planFor = (type: ChannelType) => PLANS.find((p) => p.limits.channels.includes(type))

export function ChannelsSection() {
  const { user } = useAuth()
  const allowed = limitsFor(user?.plan ?? 'FREE').channels
  const [channels, setChannels] = useState<NotificationChannel[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  const [type, setType] = useState<ChannelType>('EMAIL')
  const [target, setTarget] = useState('')
  const [targetError, setTargetError] = useState<string | null>(null)
  const [addError, setAddError] = useState<{ message: string; planLimit: boolean } | null>(null)
  const [adding, setAdding] = useState(false)
  const [added, setAdded] = useState(false)

  const [busy, setBusy] = useState<number | null>(null)
  const [tested, setTested] = useState<Record<number, 'ok' | string>>({})
  const [toDelete, setToDelete] = useState<NotificationChannel | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  useEffect(() => {
    listChannels()
      .then(setChannels)
      .catch((e: unknown) => setLoadError(e instanceof Error ? e.message : 'Failed to load channels'))
  }, [])

  const typeInfo = TYPES.find((t) => t.type === type)!
  const lockedTypes = TYPES.filter((t) => !allowed.includes(t.type))

  async function onAdd(e: FormEvent) {
    e.preventDefault()
    setAdded(false)
    setAddError(null)
    const rule = CHANNEL_RULES[type]
    const value = target.trim()
    if (!value) return setTargetError('Required')
    if (rule && !rule.test(value)) return setTargetError(rule.message)
    setTargetError(null)
    setAdding(true)
    try {
      const created = await createChannel({ type, target: value })
      setChannels((list) => [...(list ?? []), created])
      setTarget('')
      setAdded(true)
    } catch (err) {
      const { fields, banner } = readApiError(err)
      if (fields.target) setTargetError(fields.target)
      else setAddError({ message: banner ?? 'Could not add channel', planLimit: err instanceof ApiError && err.status === 403 })
    } finally {
      setAdding(false)
    }
  }

  async function toggle(c: NotificationChannel, enabled: boolean) {
    setBusy(c.id)
    try {
      const updated = await setChannelEnabled(c.id, enabled)
      setChannels((list) => list?.map((x) => (x.id === c.id ? updated : x)) ?? null)
    } finally {
      setBusy(null)
    }
  }

  async function test(c: NotificationChannel) {
    setBusy(c.id)
    setTested((t) => ({ ...t, [c.id]: '' }))
    try {
      await sendTestAlert(c.id)
      setTested((t) => ({ ...t, [c.id]: 'ok' }))
    } catch (err) {
      setTested((t) => ({ ...t, [c.id]: err instanceof Error ? err.message : 'Test failed' }))
    } finally {
      setBusy(null)
    }
  }

  async function confirmDelete() {
    if (!toDelete) return
    setDeleting(true)
    setDeleteError(null)
    try {
      await deleteChannel(toDelete.id)
      setChannels((list) => list?.filter((x) => x.id !== toDelete.id) ?? null)
      setToDelete(null)
    } catch (err) {
      setDeleteError(err instanceof Error ? err.message : 'Could not delete channel')
    } finally {
      setDeleting(false)
    }
  }

  return (
    <div className="space-y-6">
      <SettingsCard title="Alert channels" description="Every active channel gets an alert when a monitor goes down and again when it recovers.">
        {loadError ? (
          <p className="text-sm text-red-600 dark:text-red-400">{loadError}</p>
        ) : !channels ? (
          <div className="flex justify-center py-8 text-zinc-400">
            <Spinner />
          </div>
        ) : (
          <ul className="-mx-5 -mb-5 divide-y divide-zinc-200 border-t border-zinc-200 sm:-mx-6 sm:-mb-6 dark:divide-zinc-800 dark:border-zinc-800">
            {channels.map((c) => {
              const Icon = ICON[c.type]
              const included = allowed.includes(c.type)
              const result = tested[c.id]
              return (
                <li key={c.id} className="flex flex-wrap items-center gap-x-4 gap-y-2 px-5 py-3.5 sm:px-6">
                  <span
                    className={cn(
                      'flex size-9 shrink-0 items-center justify-center rounded-lg',
                      included && c.enabled
                        ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-400'
                        : 'bg-zinc-100 text-zinc-400 dark:bg-zinc-800',
                    )}
                  >
                    <Icon className="size-4" aria-hidden />
                  </span>
                  <div className="min-w-0 flex-1">
                    <p className="flex items-center gap-2 text-sm font-medium">
                      {LABEL[c.type]}
                      {!included && (
                        <Link
                          to="/billing"
                          className="inline-flex items-center gap-1 rounded-full bg-amber-50 px-2 py-0.5 text-xs font-medium text-amber-800 hover:underline dark:bg-amber-500/10 dark:text-amber-300"
                        >
                          <Lock className="size-3" aria-hidden />
                          Needs {planFor(c.type)?.name}
                        </Link>
                      )}
                    </p>
                    <p className="truncate text-sm text-zinc-500 dark:text-zinc-400" title={c.type === 'EMAIL' || c.type === 'SMS' ? c.target : undefined}>
                      {displayTarget(c)}
                    </p>
                    {result && (
                      <p className={cn('mt-0.5 text-xs', result === 'ok' ? 'text-emerald-700 dark:text-emerald-400' : 'text-red-600 dark:text-red-400')}>
                        {result === 'ok' ? 'Test alert sent — check your inbox or channel.' : result}
                      </p>
                    )}
                  </div>
                  {/* Own row on phones so the target isn't squeezed to a few characters. */}
                  <div className="flex w-full items-center justify-end gap-2 sm:w-auto">
                    <Button size="sm" variant="ghost" onClick={() => test(c)} disabled={busy === c.id || !included || !c.enabled}>
                      {busy === c.id && result === '' ? <Spinner className="size-3.5" /> : null}
                      Send test
                    </Button>
                    <Switch
                      checked={c.enabled && included}
                      onChange={(v) => toggle(c, v)}
                      disabled={busy === c.id || !included}
                      label={`${c.enabled ? 'Disable' : 'Enable'} ${LABEL[c.type]} alerts to ${displayTarget(c)}`}
                    />
                    <button
                      type="button"
                      onClick={() => {
                        setDeleteError(null)
                        setToDelete(c)
                      }}
                      className="rounded-md p-2 text-zinc-400 hover:bg-red-50 hover:text-red-600 dark:hover:bg-red-500/10 dark:hover:text-red-400"
                      aria-label={`Delete ${LABEL[c.type]} channel`}
                      title="Delete"
                    >
                      <Trash2 className="size-4" />
                    </button>
                  </div>
                </li>
              )
            })}
          </ul>
        )}
      </SettingsCard>

      <SettingsCard
        title="Add a channel"
        onSubmit={onAdd}
        success={added ? 'Channel added' : null}
        error={
          addError &&
          (addError.planLimit ? (
            <>
              {addError.message}{' '}
              <Link to="/billing" className="font-medium underline underline-offset-2">
                See plans
              </Link>
            </>
          ) : (
            addError.message
          ))
        }
        footer={
          <Button type="submit" loading={adding} disabled={!allowed.includes(type)}>
            Add channel
          </Button>
        }
      >
        <div className="max-w-xl space-y-5">
          <div role="radiogroup" aria-label="Channel type" className="grid grid-cols-3 gap-2">
            {TYPES.map((t) => {
              const locked = !allowed.includes(t.type)
              const Icon = t.icon
              return (
                <button
                  key={t.type}
                  type="button"
                  role="radio"
                  aria-checked={type === t.type}
                  onClick={() => {
                    setType(t.type)
                    setTargetError(null)
                    setAddError(null)
                    setAdded(false)
                  }}
                  className={cn(
                    'flex flex-col items-center gap-1 rounded-lg border px-3 py-3 text-sm font-medium transition-colors',
                    type === t.type
                      ? 'border-emerald-600 bg-emerald-50 text-emerald-800 dark:border-emerald-500 dark:bg-emerald-500/10 dark:text-emerald-300'
                      : 'border-zinc-200 hover:bg-zinc-50 dark:border-zinc-800 dark:hover:bg-zinc-800/50',
                  )}
                >
                  <Icon className="size-5" aria-hidden />
                  {t.label}
                  {locked && (
                    <span className="flex items-center gap-1 text-xs font-normal text-zinc-500 dark:text-zinc-400">
                      <Lock className="size-3" aria-hidden />
                      {planFor(t.type)?.name}
                    </span>
                  )}
                </button>
              )
            })}
          </div>

          {allowed.includes(type) ? (
            <Field id="channel-target" label={type === 'EMAIL' ? 'Email address' : type === 'SLACK' ? 'Webhook URL' : 'Phone number'} hint={typeInfo.hint} error={targetError ?? undefined}>
              <Input
                id="channel-target"
                type={type === 'EMAIL' ? 'email' : type === 'SMS' ? 'tel' : 'url'}
                value={target}
                onChange={(e) => {
                  setTarget(e.target.value)
                  setTargetError(null)
                  setAdded(false)
                }}
                placeholder={typeInfo.placeholder}
                className={type === 'SLACK' ? 'font-mono' : undefined}
                error={targetError ?? undefined}
              />
            </Field>
          ) : (
            <UpgradePrompt title={`${typeInfo.label} alerts are on ${planFor(type)?.name} and up`} cta={`Upgrade to ${planFor(type)?.name}`}>
              {lockedTypes.length > 1 ? 'Upgrade to reach your team where they already are.' : undefined}
            </UpgradePrompt>
          )}
        </div>
      </SettingsCard>

      <Modal
        open={!!toDelete}
        onClose={() => !deleting && setToDelete(null)}
        title="Delete channel?"
        footer={
          <>
            <Button variant="secondary" onClick={() => setToDelete(null)} disabled={deleting}>
              Cancel
            </Button>
            <Button variant="danger" onClick={confirmDelete} loading={deleting}>
              Delete
            </Button>
          </>
        }
      >
        {toDelete && (
          <>
            Alerts will stop going to <strong className="font-medium text-zinc-900 dark:text-zinc-100">{displayTarget(toDelete)}</strong>.
          </>
        )}
        {deleteError && (
          <p className="mt-3 text-red-600 dark:text-red-400" role="alert">
            {deleteError}
          </p>
        )}
      </Modal>
      {/* Screen-reader confirmation after a test send. */}
      <span className="sr-only" aria-live="polite">
        {Object.values(tested).includes('ok') ? 'Test alert sent' : ''}
      </span>
    </div>
  )
}

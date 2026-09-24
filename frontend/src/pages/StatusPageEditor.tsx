import { useEffect, useMemo, useState, type FormEvent, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ArrowDown, ArrowUp, Check, Copy, ExternalLink, Globe, Lock } from 'lucide-react'
import { listMonitors } from '../api/monitors'
import { getMyStatusPage, saveStatusPage, slugify } from '../api/statusPages'
import { useAuth } from '../auth/authContext'
import type { MonitorWithStats } from '../types/monitor'
import { displayStatus } from '../types/monitor'
import type { StatusPageConfig } from '../types/statusPage'
import { cn } from '../lib/format'
import { limitsFor, nextPlan } from '../lib/plans'
import { PageHeader } from '../components/layout/PageHeader'
import { Button } from '../components/ui/Button'
import { Card } from '../components/ui/Card'
import { EmptyState } from '../components/ui/EmptyState'
import { Field, Input, Textarea } from '../components/ui/Field'
import { Spinner } from '../components/ui/Spinner'
import { StatusBadge } from '../components/ui/StatusBadge'
import { Switch } from '../components/ui/Switch'
import { readApiError } from './auth/authForm'

type Errors = Partial<Record<'slug' | 'title' | 'description' | 'monitors', string>>

export function StatusPageEditor() {
  const { user } = useAuth()
  const [saved, setSaved] = useState<StatusPageConfig | null | undefined>(undefined)
  const [draft, setDraft] = useState<StatusPageConfig | null>(null)
  const [monitors, setMonitors] = useState<MonitorWithStats[] | null>(null)
  const [errors, setErrors] = useState<Errors>({})
  const [banner, setBanner] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [justSaved, setJustSaved] = useState(false)
  const [copied, setCopied] = useState(false)

  useEffect(() => {
    Promise.all([getMyStatusPage(), listMonitors()]).then(([page, list]) => {
      setSaved(page)
      setDraft(page)
      setMonitors(list)
    })
  }, [])

  const dirty = useMemo(() => JSON.stringify(draft) !== JSON.stringify(saved), [draft, saved])
  const publicUrl = saved ? `${window.location.origin}/status/${saved.slug}` : null
  const retention = limitsFor(user?.plan ?? 'FREE').retentionDays
  const upgrade = user ? nextPlan(user.plan) : null

  function start() {
    const base = user?.name ? slugify(user.name) : 'status'
    setDraft({
      slug: base.length >= 3 ? base : `${base}-status`,
      title: user?.name ? `${user.name} Status` : 'Status',
      description: '',
      published: false,
      monitors: (monitors ?? []).filter((m) => m.isActive).map((m) => ({ monitorId: m.id, displayName: m.name })),
    })
  }

  const update = (patch: Partial<StatusPageConfig>) => {
    setDraft((d) => (d ? { ...d, ...patch } : d))
    setJustSaved(false)
  }

  function toggleMonitor(m: MonitorWithStats, on: boolean) {
    if (!draft) return
    update({
      monitors: on
        ? [...draft.monitors, { monitorId: m.id, displayName: m.name }]
        : draft.monitors.filter((x) => x.monitorId !== m.id),
    })
  }

  function move(index: number, by: -1 | 1) {
    if (!draft) return
    const list = [...draft.monitors]
    const [item] = list.splice(index, 1)
    list.splice(index + by, 0, item)
    update({ monitors: list })
  }

  async function onSave(e: FormEvent) {
    e.preventDefault()
    if (!draft) return
    setSaving(true)
    setErrors({})
    setBanner(null)
    try {
      const result = await saveStatusPage(draft)
      setSaved(result)
      setDraft(result)
      setJustSaved(true)
    } catch (err) {
      const { fields, banner } = readApiError(err)
      setErrors(fields)
      setBanner(Object.keys(fields).length ? null : banner)
    } finally {
      setSaving(false)
    }
  }

  async function copyLink() {
    if (!publicUrl) return
    try {
      await navigator.clipboard.writeText(publicUrl)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      // Clipboard blocked; the URL is visible in the address field anyway.
    }
  }

  if (saved === undefined || !monitors) {
    return (
      <>
        <PageHeader title="Status page" />
        <div className="flex justify-center py-24 text-zinc-400">
          <Spinner />
        </div>
      </>
    )
  }

  if (!draft) {
    return (
      <>
        <PageHeader title="Status page" />
        <Card>
          <EmptyState
            icon={Globe}
            title="Share your uptime"
            description="Publish a page your customers can check during an outage, instead of emailing you. You choose which monitors appear and what they're called."
            action={<Button onClick={start}>Create status page</Button>}
          />
        </Card>
      </>
    )
  }

  const selected = new Map(draft.monitors.map((m, i) => [m.monitorId, i]))
  const unselected = monitors.filter((m) => !selected.has(m.id))
  const byId = new Map(monitors.map((m) => [m.id, m]))

  return (
    <form onSubmit={onSave} noValidate>
      <PageHeader
        title="Status page"
        description="A public, read-only page with the live status and history of the monitors you pick."
        actions={
          saved?.published &&
          publicUrl && (
            <>
              <Button type="button" variant="secondary" onClick={copyLink}>
                {copied ? <Check className="size-4" aria-hidden /> : <Copy className="size-4" aria-hidden />}
                {copied ? 'Copied' : 'Copy link'}
              </Button>
              <a
                href={publicUrl}
                target="_blank"
                rel="noopener noreferrer"
                className="inline-flex h-10 items-center gap-2 rounded-lg bg-emerald-600 px-4 text-sm font-medium whitespace-nowrap text-white hover:bg-emerald-700"
              >
                View page
                <ExternalLink className="size-4" aria-hidden />
              </a>
            </>
          )
        }
      />

      <div className="space-y-6">
        <Card className="p-5 sm:p-6">
          <div className="flex items-start justify-between gap-4">
            <div>
              <h2 className="text-base font-semibold">Visibility</h2>
              <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
                {draft.published
                  ? 'Published — anyone with the link can see it.'
                  : 'Draft — only you can edit it, and the public link shows “not found”.'}
              </p>
            </div>
            <Switch checked={draft.published} onChange={(v) => update({ published: v })} label="Publish status page" />
          </div>
        </Card>

        <Card className="p-5 sm:p-6">
          <h2 className="text-base font-semibold">Details</h2>
          <div className="mt-5 grid max-w-xl gap-5">
            <Field id="sp-title" label="Title" error={errors.title}>
              <Input id="sp-title" value={draft.title} maxLength={80} onChange={(e) => update({ title: e.target.value })} error={errors.title} />
            </Field>
            <Field id="sp-slug" label="Address" error={errors.slug} hint="Lowercase letters, numbers and dashes.">
              <div className="flex">
                <span className="inline-flex shrink-0 items-center rounded-l-lg border border-r-0 whitespace-nowrap border-zinc-300 bg-zinc-50 px-3 text-sm text-zinc-500 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-400">
                  {window.location.host}/status/
                </span>
                <Input
                  id="sp-slug"
                  value={draft.slug}
                  maxLength={40}
                  onChange={(e) => update({ slug: e.target.value.toLowerCase().replace(/[^a-z0-9-]/g, '') })}
                  className="min-w-0 rounded-l-none font-mono"
                  error={errors.slug}
                />
              </div>
            </Field>
            <Field id="sp-desc" label="Description" hint={`${draft.description.length}/280 · optional`} error={errors.description}>
              <Textarea
                id="sp-desc"
                value={draft.description}
                maxLength={280}
                rows={2}
                placeholder="Live status of our API and website."
                onChange={(e) => update({ description: e.target.value })}
                error={errors.description}
              />
            </Field>
          </div>
        </Card>

        <Card className="overflow-hidden">
          <div className="p-5 sm:p-6">
            <h2 className="text-base font-semibold">Monitors on the page</h2>
            <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
              Visitors only see the display name you choose, status and uptime — never the URL or error details.
            </p>
            <p className="mt-2 text-xs text-zinc-500 dark:text-zinc-400">
              Shows {Math.min(90, retention)} days of history on your plan.
              {retention < 90 && upgrade && (
                <>
                  {' '}
                  <Link to="/billing" className="font-medium text-emerald-700 hover:underline dark:text-emerald-400">
                    Upgrade to {upgrade.name} for 90 days
                  </Link>
                </>
              )}
            </p>
            {errors.monitors && <p className="mt-2 text-sm text-red-600 dark:text-red-400">{errors.monitors}</p>}
          </div>

          {monitors.length === 0 ? (
            <p className="border-t border-zinc-200 px-6 py-8 text-center text-sm text-zinc-500 dark:border-zinc-800 dark:text-zinc-400">
              You don't have any monitors yet.{' '}
              <Link to="/monitors/new" className="font-medium text-emerald-700 hover:underline dark:text-emerald-400">
                Add one
              </Link>{' '}
              to show it here.
            </p>
          ) : (
            <ul className="divide-y divide-zinc-200 border-t border-zinc-200 dark:divide-zinc-800 dark:border-zinc-800">
              {draft.monitors.map((pm, i) => {
                const m = byId.get(pm.monitorId)
                if (!m) return null
                return (
                  <li key={m.id} className="flex flex-col gap-3 px-5 py-3.5 sm:flex-row sm:items-center sm:px-6">
                    <div className="flex min-w-0 items-center gap-3 sm:w-64">
                      <input
                        type="checkbox"
                        checked
                        onChange={() => toggleMonitor(m, false)}
                        className="size-4 shrink-0 accent-emerald-600"
                        aria-label={`Remove ${m.name} from the status page`}
                      />
                      <div className="min-w-0">
                        <p className="truncate text-sm font-medium">{m.name}</p>
                        <p className="flex items-center gap-1 truncate text-xs text-zinc-500 dark:text-zinc-400">
                          <Lock className="size-3 shrink-0" aria-hidden />
                          <span className="truncate">{m.type === 'HEARTBEAT' ? 'Heartbeat (ping URL stays private)' : m.url}</span>
                        </p>
                      </div>
                    </div>
                    <div className="flex flex-1 items-center gap-2">
                      <Input
                        value={pm.displayName}
                        maxLength={60}
                        onChange={(e) =>
                          update({ monitors: draft.monitors.map((x, j) => (j === i ? { ...x, displayName: e.target.value } : x)) })
                        }
                        aria-label={`Public name for ${m.name}`}
                        placeholder="Public name"
                        className="h-9"
                      />
                      <span className="hidden w-24 shrink-0 sm:block">
                        <StatusBadge status={displayStatus(m)} />
                      </span>
                      <div className="flex shrink-0">
                        <IconBtn label="Move up" disabled={i === 0} onClick={() => move(i, -1)}>
                          <ArrowUp className="size-4" />
                        </IconBtn>
                        <IconBtn label="Move down" disabled={i === draft.monitors.length - 1} onClick={() => move(i, 1)}>
                          <ArrowDown className="size-4" />
                        </IconBtn>
                      </div>
                    </div>
                  </li>
                )
              })}
              {unselected.map((m) => (
                <li key={m.id} className="flex items-center gap-3 px-5 py-3.5 sm:px-6">
                  <input
                    type="checkbox"
                    checked={false}
                    onChange={() => toggleMonitor(m, true)}
                    className="size-4 shrink-0 accent-emerald-600"
                    aria-label={`Add ${m.name} to the status page`}
                  />
                  <div className="min-w-0 flex-1 opacity-70">
                    <p className="truncate text-sm font-medium">{m.name}</p>
                    <p className="truncate text-xs text-zinc-500 dark:text-zinc-400">Not shown</p>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>

      {/* Save bar: sticks to the bottom while there are unsaved changes. */}
      <div
        className={cn(
          'z-20 mt-6 flex items-center justify-end gap-3 rounded-xl border px-4 py-3 transition-colors',
          // Only float over the form while there's something to save.
          dirty
            ? 'sticky bottom-4 border-zinc-200 bg-white shadow-lg dark:border-zinc-700 dark:bg-zinc-900'
            : 'border-transparent bg-transparent',
        )}
      >
        {banner && <p className="mr-auto text-sm text-red-600 dark:text-red-400">{banner}</p>}
        {!banner && dirty && <p className="mr-auto text-sm text-zinc-600 dark:text-zinc-400">Unsaved changes</p>}
        {!banner && !dirty && justSaved && (
          <p className="mr-auto flex items-center gap-1.5 text-sm text-emerald-700 dark:text-emerald-400" role="status">
            <Check className="size-4" aria-hidden /> Saved
          </p>
        )}
        {dirty && saved && (
          <Button type="button" variant="ghost" onClick={() => setDraft(saved)} disabled={saving}>
            Discard
          </Button>
        )}
        <Button type="submit" loading={saving} disabled={!dirty}>
          {saved ? 'Save changes' : 'Create page'}
        </Button>
      </div>
    </form>
  )
}

function IconBtn({ label, disabled, onClick, children }: { label: string; disabled?: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      title={label}
      aria-label={label}
      disabled={disabled}
      onClick={onClick}
      className="rounded-md p-1.5 text-zinc-500 hover:bg-zinc-100 hover:text-zinc-900 disabled:opacity-30 disabled:hover:bg-transparent dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-white"
    >
      {children}
    </button>
  )
}

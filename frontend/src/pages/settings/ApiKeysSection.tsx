import { useEffect, useState, type FormEvent } from 'react'
import { AlertTriangle, Check, Copy, KeyRound, Trash2 } from 'lucide-react'
import { createApiKey, listApiKeys, revokeApiKey } from '../../api/apiKeys'
import type { ApiKey, CreatedApiKey } from '../../types/apiKey'
import { API_BASE_URL, formatDay, timeAgo } from '../../lib/format'
import { Button } from '../../components/ui/Button'
import { Field, Input } from '../../components/ui/Field'
import { Modal } from '../../components/ui/Modal'
import { Spinner } from '../../components/ui/Spinner'
import { readApiError } from '../auth/authForm'
import { SettingsCard } from './SettingsCard'

function useCopy() {
  const [copied, setCopied] = useState(false)
  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text)
      setCopied(true)
      setTimeout(() => setCopied(false), 1800)
    } catch {
      // Clipboard blocked; the key is selectable.
    }
  }
  return [copied, copy] as const
}

export function ApiKeysSection() {
  const [keys, setKeys] = useState<ApiKey[] | null>(null)
  const [name, setName] = useState('')
  const [nameError, setNameError] = useState<string | null>(null)
  const [banner, setBanner] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [created, setCreated] = useState<CreatedApiKey | null>(null)
  const [toRevoke, setToRevoke] = useState<ApiKey | null>(null)
  const [revoking, setRevoking] = useState(false)
  const [copied, copy] = useCopy()

  useEffect(() => {
    listApiKeys().then(setKeys)
  }, [])

  async function onCreate(e: FormEvent) {
    e.preventDefault()
    setNameError(null)
    setBanner(null)
    if (!name.trim()) return setNameError('Give the key a name so you know what uses it')
    setCreating(true)
    try {
      const result = await createApiKey(name)
      setCreated(result)
      setKeys((k) => [result.apiKey, ...(k ?? [])])
      setName('')
    } catch (err) {
      const { fields, banner } = readApiError(err)
      setNameError(fields.name ?? null)
      setBanner(fields.name ? null : banner)
    } finally {
      setCreating(false)
    }
  }

  async function confirmRevoke() {
    if (!toRevoke) return
    setRevoking(true)
    try {
      await revokeApiKey(toRevoke.id)
      setKeys((k) => k?.filter((x) => x.id !== toRevoke.id) ?? null)
      if (created?.apiKey.id === toRevoke.id) setCreated(null)
      setToRevoke(null)
    } finally {
      setRevoking(false)
    }
  }

  const example = `curl ${API_BASE_URL}/api/monitors \\\n  -H "Authorization: Bearer ${created?.secret ?? 'pg_live_…'}"`

  return (
    <div className="space-y-6">
      <SettingsCard
        title="API keys"
        description="Call the PulseGuard API from scripts, CI or Terraform. A key can do anything you can, so treat it like a password."
        onSubmit={onCreate}
        error={banner}
        footer={
          <Button type="submit" loading={creating}>
            {!creating && <KeyRound className="size-4" aria-hidden />}
            Create key
          </Button>
        }
      >
        <div className="max-w-md">
          <Field id="key-name" label="Name" hint="What will use it, e.g. “GitHub Actions deploy”." error={nameError ?? undefined}>
            <Input
              id="key-name"
              value={name}
              maxLength={50}
              onChange={(e) => {
                setName(e.target.value)
                setNameError(null)
              }}
              error={nameError ?? undefined}
            />
          </Field>
        </div>
      </SettingsCard>

      {created && (
        <div className="rounded-xl border border-amber-300 bg-amber-50 p-5 dark:border-amber-500/40 dark:bg-amber-500/10" role="status">
          <p className="flex items-center gap-2 font-semibold text-amber-900 dark:text-amber-100">
            <AlertTriangle className="size-4" aria-hidden />
            Copy “{created.apiKey.name}” now — you won't see it again
          </p>
          <p className="mt-1 text-sm text-amber-900/80 dark:text-amber-100/80">
            We only keep a fingerprint of it. If you lose it, revoke it and create a new one.
          </p>
          <div className="mt-3 flex items-center gap-2 rounded-lg border border-amber-200 bg-white py-1.5 pr-1.5 pl-3 dark:border-amber-500/30 dark:bg-zinc-950">
            <code className="min-w-0 flex-1 truncate font-mono text-sm">{created.secret}</code>
            <Button size="sm" variant="secondary" onClick={() => copy(created.secret)} aria-label="Copy API key">
              {copied ? <Check className="size-3.5" aria-hidden /> : <Copy className="size-3.5" aria-hidden />}
              {copied ? 'Copied' : 'Copy'}
            </Button>
          </div>
          <div className="mt-3 flex justify-end">
            <Button size="sm" variant="ghost" onClick={() => setCreated(null)}>
              I've saved it
            </Button>
          </div>
        </div>
      )}

      <SettingsCard title="Your keys">
        {!keys ? (
          <div className="flex justify-center py-6 text-zinc-400">
            <Spinner />
          </div>
        ) : keys.length === 0 ? (
          <p className="text-sm text-zinc-500 dark:text-zinc-400">No keys yet.</p>
        ) : (
          <ul className="-mx-5 -mb-5 divide-y divide-zinc-200 border-t border-zinc-200 sm:-mx-6 sm:-mb-6 dark:divide-zinc-800 dark:border-zinc-800">
            {keys.map((k) => (
              <li key={k.id} className="flex items-center gap-4 px-5 py-3.5 sm:px-6">
                <KeyRound className="size-4 shrink-0 text-zinc-400" aria-hidden />
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm font-medium">{k.name}</p>
                  <p className="text-xs text-zinc-500 dark:text-zinc-400">
                    <code className="font-mono">{k.prefix}••••</code> · Created {formatDay(k.createdAt)} ·{' '}
                    {k.lastUsedAt ? `Last used ${timeAgo(k.lastUsedAt)}` : 'Never used'}
                  </p>
                </div>
                <Button size="sm" variant="ghost" onClick={() => setToRevoke(k)} className="text-red-600 hover:text-red-700 dark:text-red-400">
                  <Trash2 className="size-3.5" aria-hidden />
                  Revoke
                </Button>
              </li>
            ))}
          </ul>
        )}
      </SettingsCard>

      <SettingsCard title="Using a key" description="Send it as a bearer token. Same endpoints and limits as the app.">
        <pre className="overflow-x-auto rounded-lg bg-zinc-950 p-3 text-xs leading-relaxed text-zinc-100">
          <code>{example}</code>
        </pre>
      </SettingsCard>

      <Modal
        open={!!toRevoke}
        onClose={() => !revoking && setToRevoke(null)}
        title="Revoke this key?"
        footer={
          <>
            <Button variant="secondary" onClick={() => setToRevoke(null)} disabled={revoking}>
              Cancel
            </Button>
            <Button variant="danger" onClick={confirmRevoke} loading={revoking}>
              Revoke key
            </Button>
          </>
        }
      >
        Anything using <strong className="font-medium text-zinc-900 dark:text-zinc-100">{toRevoke?.name}</strong> will stop working
        immediately. This can't be undone.
      </Modal>
    </div>
  )
}

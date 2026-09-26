import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { updateProfile } from '../../api/auth'
import { useAuth } from '../../auth/authContext'
import { PLAN_LABEL } from '../../types/auth'
import { Button } from '../../components/ui/Button'
import { Field, Input } from '../../components/ui/Field'
import { readApiError } from '../auth/authForm'
import { SettingsCard } from './SettingsCard'

const joined = new Intl.DateTimeFormat(undefined, { month: 'long', year: 'numeric' })

export function ProfileSection() {
  const { user } = useAuth()
  const [name, setName] = useState(user?.name ?? '')
  const [error, setError] = useState<string | null>(null)
  const [saved, setSaved] = useState(false)
  const [saving, setSaving] = useState(false)

  if (!user) return null
  const dirty = name.trim() !== user.name

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setSaved(false)
    if (!name.trim()) {
      setError('Name is required')
      return
    }
    setSaving(true)
    setError(null)
    try {
      await updateProfile({ name })
      setSaved(true)
    } catch (err) {
      const { fields, banner } = readApiError(err)
      setError(fields.name ?? banner)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="space-y-6">
      <SettingsCard
        title="Profile"
        description="How you appear in PulseGuard."
        onSubmit={onSubmit}
        success={saved && !dirty ? 'Saved' : null}
        footer={
          <Button type="submit" loading={saving} disabled={!dirty}>
            Save changes
          </Button>
        }
      >
        <div className="grid max-w-xl gap-5">
          <Field id="profile-name" label="Name" error={error ?? undefined}>
            <Input
              id="profile-name"
              value={name}
              maxLength={100}
              autoComplete="name"
              onChange={(e) => {
                setName(e.target.value)
                setSaved(false)
                setError(null)
              }}
              error={error ?? undefined}
            />
          </Field>
          <Field id="profile-email" label="Email" hint="Used to sign in. Changing it isn't supported yet.">
            <Input id="profile-email" value={user.email} readOnly disabled className="cursor-not-allowed opacity-70" />
          </Field>
        </div>
      </SettingsCard>

      <SettingsCard title="Account">
        <dl className="grid gap-4 text-sm sm:grid-cols-2">
          <div>
            <dt className="text-xs text-zinc-500 dark:text-zinc-400">Plan</dt>
            <dd className="mt-0.5 font-medium">
              {PLAN_LABEL[user.plan]}{' '}
              <Link to="/billing" className="ml-1 text-sm font-medium text-emerald-700 hover:underline dark:text-emerald-400">
                Manage
              </Link>
            </dd>
          </div>
          <div>
            <dt className="text-xs text-zinc-500 dark:text-zinc-400">Member since</dt>
            <dd className="mt-0.5 font-medium">{joined.format(new Date(user.createdAt))}</dd>
          </div>
        </dl>
      </SettingsCard>
    </div>
  )
}

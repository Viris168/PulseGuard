import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { requestEmailChange, updateProfile } from '../../api/auth'
import { useAuth } from '../../auth/authContext'
import { PLAN_LABEL } from '../../types/auth'
import { Button } from '../../components/ui/Button'
import { Field, Input, PasswordInput } from '../../components/ui/Field'
import { EMAIL_RE, readApiError } from '../auth/authForm'
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
          <Field id="profile-email" label="Email" hint={user.emailVerified ? 'Used to sign in and for alerts. Verified.' : 'Used to sign in and for alerts. Not verified yet.'}>
            <Input id="profile-email" value={user.email} readOnly disabled className="cursor-not-allowed opacity-70" />
          </Field>
        </div>
      </SettingsCard>

      <ChangeEmailCard currentEmail={user.email} />

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

type EmailFields = 'newEmail' | 'currentPassword'

/** Two steps: a link goes to the new address, and nothing changes until it is used. */
function ChangeEmailCard({ currentEmail }: { currentEmail: string }) {
  const [form, setForm] = useState<Record<EmailFields, string>>({ newEmail: '', currentPassword: '' })
  const [errors, setErrors] = useState<Partial<Record<EmailFields, string>>>({})
  const [banner, setBanner] = useState<string | null>(null)
  const [sentTo, setSentTo] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const set = (k: EmailFields, v: string) => {
    setForm((f) => ({ ...f, [k]: v }))
    setErrors((e) => ({ ...e, [k]: undefined }))
    setSentTo(null)
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setBanner(null)
    const found: Partial<Record<EmailFields, string>> = {}
    const newEmail = form.newEmail.trim()
    if (!EMAIL_RE.test(newEmail)) found.newEmail = 'Enter a valid email address'
    else if (newEmail.toLowerCase() === currentEmail.toLowerCase()) found.newEmail = "That's already your email"
    if (!form.currentPassword) found.currentPassword = 'Enter your current password'
    setErrors(found)
    if (Object.keys(found).length) return

    setSaving(true)
    try {
      await requestEmailChange(newEmail, form.currentPassword)
      setSentTo(newEmail)
      setForm({ newEmail: '', currentPassword: '' })
    } catch (err) {
      const { fields, banner } = readApiError(err)
      setErrors(fields)
      setBanner(banner)
    } finally {
      setSaving(false)
    }
  }

  return (
    <SettingsCard
      title="Change email"
      description="We'll send a link to the new address. Your email only changes once you open it, and we'll let your current address know."
      onSubmit={onSubmit}
      error={banner}
      success={sentTo ? `Check ${sentTo} for the confirmation link. It expires in 24 hours.` : null}
      footer={
        <Button type="submit" loading={saving}>
          Send confirmation link
        </Button>
      }
    >
      <div className="grid max-w-md gap-5">
        <Field id="new-email" label="New email" error={errors.newEmail}>
          <Input
            id="new-email"
            type="email"
            autoComplete="email"
            value={form.newEmail}
            onChange={(e) => set('newEmail', e.target.value)}
            error={errors.newEmail}
          />
        </Field>
        <Field id="email-password" label="Current password" error={errors.currentPassword}>
          <PasswordInput
            id="email-password"
            autoComplete="current-password"
            value={form.currentPassword}
            onChange={(e) => set('currentPassword', e.target.value)}
            error={errors.currentPassword}
          />
        </Field>
      </div>
    </SettingsCard>
  )
}

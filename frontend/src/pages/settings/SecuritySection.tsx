import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { LogOut } from 'lucide-react'
import { changePassword } from '../../api/auth'
import { ApiError } from '../../api/errors'
import { useAuth } from '../../auth/authContext'
import { Button } from '../../components/ui/Button'
import { Field, PasswordInput } from '../../components/ui/Field'
import { readApiError } from '../auth/authForm'
import { SettingsCard } from './SettingsCard'

type FieldName = 'currentPassword' | 'newPassword' | 'confirm'
type Errors = Partial<Record<FieldName, string>>

const EMPTY: Record<FieldName, string> = { currentPassword: '', newPassword: '', confirm: '' }

// Same limits as ChangePasswordRequest.
function validate(f: Record<FieldName, string>): Errors {
  const e: Errors = {}
  if (!f.currentPassword) e.currentPassword = 'Enter your current password'
  if (!f.newPassword) e.newPassword = 'Enter a new password'
  else if (f.newPassword.length < 8 || f.newPassword.length > 72) e.newPassword = 'Use 8 to 72 characters'
  if (f.confirm !== f.newPassword) e.confirm = "Passwords don't match"
  return e
}

export function SecuritySection() {
  const { logout } = useAuth()
  const navigate = useNavigate()
  const [form, setForm] = useState(EMPTY)
  const [errors, setErrors] = useState<Errors>({})
  const [banner, setBanner] = useState<string | null>(null)
  const [done, setDone] = useState(false)
  const [saving, setSaving] = useState(false)
  const [signingOut, setSigningOut] = useState(false)

  const set = (k: FieldName, v: string) => {
    setForm((f) => ({ ...f, [k]: v }))
    setDone(false)
  }

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    const found = validate(form)
    setErrors(found)
    setBanner(null)
    setDone(false)
    if (Object.keys(found).length) return

    setSaving(true)
    try {
      await changePassword({ currentPassword: form.currentPassword, newPassword: form.newPassword })
      setForm(EMPTY)
      setDone(true)
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        // The backend's generic credentials error; here we know which field it means.
        setErrors({ currentPassword: 'Current password is incorrect' })
      } else if (err instanceof ApiError && err.status === 400 && !Object.keys(err.fieldErrors).length) {
        setErrors({ newPassword: err.message })
      } else {
        const { fields, banner } = readApiError(err)
        setErrors(fields)
        setBanner(banner)
      }
    } finally {
      setSaving(false)
    }
  }

  async function signOutEverywhere() {
    setSigningOut(true)
    await logout()
    navigate('/login', { replace: true })
  }

  return (
    <div className="space-y-6">
      <SettingsCard
        title="Change password"
        description="You'll stay signed in here. Every other device is signed out."
        onSubmit={onSubmit}
        error={banner}
        success={done ? 'Password changed. Other devices were signed out.' : null}
        footer={
          <Button type="submit" loading={saving}>
            Update password
          </Button>
        }
      >
        <div className="grid max-w-md gap-5">
          <Field id="current-password" label="Current password" error={errors.currentPassword}>
            <PasswordInput
              id="current-password"
              autoComplete="current-password"
              value={form.currentPassword}
              onChange={(e) => set('currentPassword', e.target.value)}
              error={errors.currentPassword}
            />
          </Field>
          <Field id="new-password" label="New password" hint="8 to 72 characters" error={errors.newPassword}>
            <PasswordInput
              id="new-password"
              autoComplete="new-password"
              maxLength={72}
              value={form.newPassword}
              onChange={(e) => set('newPassword', e.target.value)}
              error={errors.newPassword}
            />
          </Field>
          <Field id="confirm-password" label="Confirm new password" error={errors.confirm}>
            <PasswordInput
              id="confirm-password"
              autoComplete="new-password"
              maxLength={72}
              value={form.confirm}
              onChange={(e) => set('confirm', e.target.value)}
              error={errors.confirm}
            />
          </Field>
        </div>
      </SettingsCard>

      <SettingsCard
        title="Sessions"
        description="Lost a laptop or signed in on a shared computer? End every session, including this one. You'll sign in again everywhere."
        footer={
          <Button variant="secondary" onClick={signOutEverywhere} loading={signingOut}>
            {!signingOut && <LogOut className="size-4" aria-hidden />}
            Sign out of all devices
          </Button>
        }
      />
    </div>
  )
}

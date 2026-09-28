import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { LogOut, Trash2 } from 'lucide-react'
import { changePassword, deleteAccount } from '../../api/auth'
import { ApiError } from '../../api/errors'
import { useAuth } from '../../auth/authContext'
import { Button } from '../../components/ui/Button'
import { Field, PasswordInput } from '../../components/ui/Field'
import { Modal } from '../../components/ui/Modal'
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
      // A wrong current password comes back as a field error on currentPassword.
      if (err instanceof ApiError && err.status === 400 && !Object.keys(err.fieldErrors).length) {
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
        description="You'll stay signed in here. Every other device is signed out, and your API keys are revoked."
        onSubmit={onSubmit}
        error={banner}
        success={done ? 'Password changed. Other devices were signed out and your API keys revoked.' : null}
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

      <DeleteAccountCard />
    </div>
  )
}

/** Cancels the subscription, then deletes the account and everything in it. */
function DeleteAccountCard() {
  const navigate = useNavigate()
  const [open, setOpen] = useState(false)
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [deleting, setDeleting] = useState(false)

  function close() {
    if (deleting) return
    setOpen(false)
    setPassword('')
    setError(null)
  }

  async function confirm() {
    if (!password) return setError('Enter your current password')
    setDeleting(true)
    setError(null)
    try {
      await deleteAccount(password)
      navigate('/', { replace: true })
    } catch (err) {
      const { fields, banner } = readApiError(err)
      setError(fields.currentPassword ?? banner ?? 'Could not delete the account')
      setDeleting(false)
    }
  }

  return (
    <>
      <SettingsCard
        title="Delete account"
        description="Deletes your monitors, their history, incidents, alert channels, API keys and status page, and cancels your subscription straight away. This can't be undone."
        footer={
          <Button variant="danger" onClick={() => setOpen(true)}>
            <Trash2 className="size-4" aria-hidden />
            Delete account
          </Button>
        }
      />
      <Modal
        open={open}
        onClose={close}
        title="Delete your account?"
        footer={
          <>
            <Button variant="secondary" onClick={close} disabled={deleting}>
              Cancel
            </Button>
            <Button variant="danger" onClick={confirm} loading={deleting}>
              Delete everything
            </Button>
          </>
        }
      >
        <p>Everything in this account is removed for good, and any paid plan ends now with no further charges.</p>
        <div className="mt-4">
          <Field id="delete-password" label="Current password" error={error ?? undefined}>
            <PasswordInput
              id="delete-password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => {
                setPassword(e.target.value)
                setError(null)
              }}
              error={error ?? undefined}
            />
          </Field>
        </div>
      </Modal>
    </>
  )
}

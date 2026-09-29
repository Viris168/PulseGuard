import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/errors'
import { user as fixtureUser } from '../../test/fixtures'
import { SignupPage } from '../auth/SignupPage'
import { ApiKeysSection } from './ApiKeysSection'
import { ProfileSection } from './ProfileSection'
import { SecuritySection } from './SecuritySection'

const keysApi = vi.hoisted(() => ({ listApiKeys: vi.fn(), createApiKey: vi.fn(), revokeApiKey: vi.fn() }))
const authApi = vi.hoisted(() => ({
  requestEmailChange: vi.fn(),
  updateProfile: vi.fn(),
  changePassword: vi.fn(),
  deleteAccount: vi.fn(),
}))
const session = vi.hoisted(() => ({ register: vi.fn(), logout: vi.fn() }))
vi.mock('../../api/apiKeys', () => keysApi)
vi.mock('../../api/auth', () => authApi)
vi.mock('../../auth/authContext', () => ({
  useAuth: () => ({ user: fixtureUser(), register: session.register, logout: session.logout }),
}))

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/" element={<p>Landing page</p>} />
        <Route path="/keys" element={<ApiKeysSection />} />
        <Route path="/profile" element={<ProfileSection />} />
        <Route path="/security" element={<SecuritySection />} />
        <Route path="/signup" element={<SignupPage />} />
      </Routes>
    </MemoryRouter>,
  )
  return userEvent.setup()
}

const SECRET = 'pg_live_AbCdEfGhIjKlMnOpQrStUvWxYz012345'

beforeEach(() => {
  keysApi.listApiKeys.mockResolvedValue([
    { id: 1, name: 'GitHub Actions', prefix: 'pg_live_Qw3r', createdAt: '2026-01-01T00:00:00Z', lastUsedAt: null },
  ])
  keysApi.createApiKey.mockResolvedValue({
    apiKey: { id: 2, name: 'Terraform', prefix: SECRET.slice(0, 12), createdAt: '2026-01-02T00:00:00Z', lastUsedAt: null },
    secret: SECRET,
  })
  keysApi.revokeApiKey.mockResolvedValue(undefined)
  authApi.requestEmailChange.mockResolvedValue(undefined)
  authApi.deleteAccount.mockResolvedValue(undefined)
  session.register.mockResolvedValue(undefined)
})

describe('API keys', () => {
  it('shows a new key once, and lists it by its prefix only', async () => {
    const ui = renderAt('/keys')
    await screen.findByText('GitHub Actions')

    await ui.type(screen.getByLabelText('Name'), 'Terraform')
    await ui.click(screen.getByRole('button', { name: /Create key/ }))

    expect(await screen.findByText(/you won't see it again/)).toBeInTheDocument()
    expect(screen.getAllByText(SECRET).length).toBeGreaterThan(0)
    expect(keysApi.createApiKey).toHaveBeenCalledWith('Terraform')

    await ui.click(screen.getByRole('button', { name: "I've saved it" }))
    expect(screen.queryByText(/you won't see it again/)).not.toBeInTheDocument()
    expect(screen.getByText(/pg_live_AbCd/)).toBeInTheDocument()
  })

  it('revokes a key after confirming', async () => {
    const ui = renderAt('/keys')
    const row = (await screen.findByText('GitHub Actions')).closest('li') as HTMLElement

    await ui.click(within(row).getByRole('button', { name: /Revoke/ }))
    await ui.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Revoke key' }))

    expect(keysApi.revokeApiKey).toHaveBeenCalledWith(1)
    await waitFor(() => expect(screen.queryByText('GitHub Actions')).not.toBeInTheDocument())
  })
})

describe('Change email', () => {
  it('sends a confirmation link to the new address', async () => {
    const ui = renderAt('/profile')

    await ui.type(screen.getByLabelText('New email'), 'new@example.com')
    await ui.type(screen.getByLabelText('Current password'), 'Sup3rSecret!')
    await ui.click(screen.getByRole('button', { name: 'Send confirmation link' }))

    expect(authApi.requestEmailChange).toHaveBeenCalledWith('new@example.com', 'Sup3rSecret!')
    expect(await screen.findByText(/Check new@example.com for the confirmation link/)).toBeInTheDocument()
  })

  it('shows a wrong password on the field, without signing out', async () => {
    authApi.requestEmailChange.mockRejectedValue(
      new ApiError(400, 'Validation failed', { currentPassword: 'Current password is incorrect' }),
    )
    const ui = renderAt('/profile')

    await ui.type(screen.getByLabelText('New email'), 'new@example.com')
    await ui.type(screen.getByLabelText('Current password'), 'wrong')
    await ui.click(screen.getByRole('button', { name: 'Send confirmation link' }))

    expect(await screen.findByText('Current password is incorrect')).toBeInTheDocument()
    expect(session.logout).not.toHaveBeenCalled()
  })

  it('refuses the address already in use', async () => {
    const ui = renderAt('/profile')

    await ui.type(screen.getByLabelText('New email'), 'alice@example.com')
    await ui.type(screen.getByLabelText('Current password'), 'Sup3rSecret!')
    await ui.click(screen.getByRole('button', { name: 'Send confirmation link' }))

    expect(screen.getByText("That's already your email")).toBeInTheDocument()
    expect(authApi.requestEmailChange).not.toHaveBeenCalled()
  })
})

describe('Delete account', () => {
  it('needs the password, then leaves the app', async () => {
    const ui = renderAt('/security')

    await ui.click(screen.getByRole('button', { name: /Delete account/ }))
    const dialog = screen.getByRole('dialog')
    await ui.click(within(dialog).getByRole('button', { name: 'Delete everything' }))
    expect(within(dialog).getByText('Enter your current password')).toBeInTheDocument()
    expect(authApi.deleteAccount).not.toHaveBeenCalled()

    await ui.type(within(dialog).getByLabelText('Current password'), 'Sup3rSecret!')
    await ui.click(within(dialog).getByRole('button', { name: 'Delete everything' }))

    expect(authApi.deleteAccount).toHaveBeenCalledWith('Sup3rSecret!')
    expect(await screen.findByText('Landing page')).toBeInTheDocument()
  })

  it('keeps the account when Stripe cannot cancel, and says why', async () => {
    authApi.deleteAccount.mockRejectedValue(
      new ApiError(502, "We couldn't cancel your subscription with Stripe, so nothing was deleted. Try again in a minute."),
    )
    const ui = renderAt('/security')

    await ui.click(screen.getByRole('button', { name: /Delete account/ }))
    const dialog = screen.getByRole('dialog')
    await ui.type(within(dialog).getByLabelText('Current password'), 'Sup3rSecret!')
    await ui.click(within(dialog).getByRole('button', { name: 'Delete everything' }))

    expect(await within(dialog).findByText(/nothing was deleted/)).toBeInTheDocument()
    expect(screen.queryByText('Landing page')).not.toBeInTheDocument()
  })
})

describe('Sign up', () => {
  it('checks the form before creating the account', async () => {
    const ui = renderAt('/signup')

    await ui.click(screen.getByRole('button', { name: 'Create account' }))

    expect(screen.getByText('Name is required')).toBeInTheDocument()
    expect(session.register).not.toHaveBeenCalled()
  })

  it('creates the account', async () => {
    const ui = renderAt('/signup')

    await ui.type(screen.getByLabelText('Name'), 'Dara')
    await ui.type(screen.getByLabelText('Work email'), 'dara@example.com')
    await ui.type(screen.getByLabelText('Password'), 'Sup3rSecret!')
    await ui.click(screen.getByRole('button', { name: 'Create account' }))

    expect(session.register).toHaveBeenCalledWith({ name: 'Dara', email: 'dara@example.com', password: 'Sup3rSecret!' })
  })

  it('points out an address that already has an account', async () => {
    session.register.mockRejectedValue(new ApiError(409, 'Email already registered'))
    const ui = renderAt('/signup')

    await ui.type(screen.getByLabelText('Name'), 'Dara')
    await ui.type(screen.getByLabelText('Work email'), 'dara@example.com')
    await ui.type(screen.getByLabelText('Password'), 'Sup3rSecret!')
    await ui.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByText('An account with this email already exists')).toBeInTheDocument()
  })
})

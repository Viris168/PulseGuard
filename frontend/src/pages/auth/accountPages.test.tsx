import { StrictMode } from 'react'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/errors'
import { EmailLinkPage } from './EmailLinkPage'
import { ForgotPasswordPage } from './ForgotPasswordPage'
import { LoginPage } from './LoginPage'
import { ResetPasswordPage } from './ResetPasswordPage'

const auth = vi.hoisted(() => ({
  requestPasswordReset: vi.fn(),
  resetPassword: vi.fn(),
  verifyEmail: vi.fn(),
  confirmEmailChange: vi.fn(),
  me: vi.fn(),
}))
vi.mock('../../api/auth', () => auth)
vi.mock('../../api/channels', () => ({ confirmChannel: vi.fn() }))
vi.mock('../../auth/authContext', () => ({ useAuth: () => ({ login: vi.fn() }) }))

function renderAt(path: string, strict = false) {
  const tree = (
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/forgot-password" element={<ForgotPasswordPage />} />
        <Route path="/reset-password" element={<ResetPasswordPage />} />
        <Route path="/verify-email" element={<EmailLinkPage mode="verify" />} />
        <Route path="/confirm-email" element={<EmailLinkPage mode="change" />} />
      </Routes>
    </MemoryRouter>
  )
  render(strict ? <StrictMode>{tree}</StrictMode> : tree)
  return userEvent.setup()
}

beforeEach(() => {
  auth.requestPasswordReset.mockResolvedValue(undefined)
  auth.resetPassword.mockResolvedValue(undefined)
  auth.verifyEmail.mockResolvedValue(undefined)
  auth.confirmEmailChange.mockResolvedValue(undefined)
})

describe('Login page', () => {
  it('links to "Forgot password?"', async () => {
    const user = renderAt('/login')

    await user.click(screen.getByRole('link', { name: 'Forgot password?' }))

    expect(screen.getByRole('heading', { name: 'Reset your password' })).toBeInTheDocument()
  })
})

describe('Forgot password', () => {
  it('checks the address before asking the server', async () => {
    const user = renderAt('/forgot-password')

    await user.type(screen.getByLabelText('Email'), 'not-an-email')
    await user.click(screen.getByRole('button', { name: 'Send reset link' }))

    expect(screen.getByText('Enter a valid email address')).toBeInTheDocument()
    expect(auth.requestPasswordReset).not.toHaveBeenCalled()
  })

  it('answers the same way whether or not the address has an account', async () => {
    const user = renderAt('/forgot-password')

    await user.type(screen.getByLabelText('Email'), 'alice@example.com')
    await user.click(screen.getByRole('button', { name: 'Send reset link' }))

    expect(await screen.findByRole('heading', { name: 'Check your email' })).toBeInTheDocument()
    expect(screen.getByText(/If an account exists for/)).toBeInTheDocument()
    expect(auth.requestPasswordReset).toHaveBeenCalledWith('alice@example.com')
  })

  it('shows the rate limit message', async () => {
    auth.requestPasswordReset.mockRejectedValueOnce(new ApiError(429, 'Too many reset requests. Try again later.'))
    const user = renderAt('/forgot-password')

    await user.type(screen.getByLabelText('Email'), 'alice@example.com')
    await user.click(screen.getByRole('button', { name: 'Send reset link' }))

    expect(await screen.findByText('Too many reset requests. Try again later.')).toBeInTheDocument()
  })
})

describe('Reset password', () => {
  it('says so when the link has no token', () => {
    renderAt('/reset-password')

    expect(screen.getByRole('heading', { name: 'This link is incomplete' })).toBeInTheDocument()
  })

  it('requires 8 characters', async () => {
    const user = renderAt('/reset-password?token=abc')

    await user.type(screen.getByLabelText('New password'), 'short')
    await user.click(screen.getByRole('button', { name: 'Set new password' }))

    expect(screen.getByText('Use at least 8 characters')).toBeInTheDocument()
    expect(auth.resetPassword).not.toHaveBeenCalled()
  })

  it('sets the password and lands on sign-in with a confirmation', async () => {
    const user = renderAt('/reset-password?token=abc')

    await user.type(screen.getByLabelText('New password'), 'brand-new-password')
    await user.click(screen.getByRole('button', { name: 'Set new password' }))

    expect(await screen.findByText('Password changed. Sign in with your new password.')).toBeInTheDocument()
    expect(auth.resetPassword).toHaveBeenCalledWith('abc', 'brand-new-password')
  })

  it('offers a new link when this one is used up', async () => {
    auth.resetPassword.mockRejectedValueOnce(new ApiError(400, 'This reset link is invalid or has expired. Request a new one.'))
    const user = renderAt('/reset-password?token=abc')

    await user.type(screen.getByLabelText('New password'), 'brand-new-password')
    await user.click(screen.getByRole('button', { name: 'Set new password' }))

    expect(await screen.findByText(/This reset link is invalid or has expired/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Request a new link' })).toBeInTheDocument()
  })
})

describe('Emailed links', () => {
  it('spends a single-use token once, even when React runs effects twice', async () => {
    renderAt('/verify-email?token=once', true)

    expect(await screen.findByRole('heading', { name: 'Email verified' })).toBeInTheDocument()
    expect(auth.verifyEmail).toHaveBeenCalledTimes(1)
    expect(auth.verifyEmail).toHaveBeenCalledWith('once')
  })

  it('confirms an email change', async () => {
    renderAt('/confirm-email?token=xyz')

    expect(await screen.findByRole('heading', { name: 'Email changed' })).toBeInTheDocument()
    expect(auth.confirmEmailChange).toHaveBeenCalledWith('xyz')
  })

  it('explains an expired or used link', async () => {
    auth.verifyEmail.mockRejectedValueOnce(new ApiError(400, 'This link is invalid or has expired.'))
    renderAt('/verify-email?token=old')

    expect(await screen.findByRole('heading', { name: "That link didn't work" })).toBeInTheDocument()
    expect(screen.getByText('This link is invalid or has expired.')).toBeInTheDocument()
  })

  it('does not call the server without a token', async () => {
    renderAt('/verify-email')

    await waitFor(() => expect(screen.getByText('This link is incomplete.')).toBeInTheDocument())
    expect(auth.verifyEmail).not.toHaveBeenCalled()
  })
})

/**
 * Auth API — live against AuthController (register, login, refresh via http.ts, logout, me,
 * profile, password).
 */
import type { AuthResponse, ChangePasswordRequest, LoginRequest, RegisterRequest, User } from '../types/auth'
import { api } from './http'
import { getSession, setSession, updateSessionUser } from './session'

/** POST /api/auth/register → 201 */
export async function register(req: RegisterRequest): Promise<AuthResponse> {
  const res = await api<AuthResponse>('/api/auth/register', { method: 'POST', body: req, auth: false })
  setSession(res)
  return res
}

/** POST /api/auth/login */
export async function login(req: LoginRequest): Promise<AuthResponse> {
  const res = await api<AuthResponse>('/api/auth/login', { method: 'POST', body: req, auth: false })
  setSession(res)
  return res
}

/** POST /api/auth/logout — ends every session for the caller. Local state is cleared either way. */
export async function logout(): Promise<void> {
  if (!getSession()) return
  try {
    await api<void>('/api/auth/logout', { method: 'POST' })
  } catch {
    // Already expired or server unreachable: signing out locally is still what the user asked for.
  } finally {
    setSession(null)
  }
}

/** GET /api/auth/me — also how the app checks a stored token is still good on startup. */
export async function me(): Promise<User> {
  return api<User>('/api/auth/me')
}

/**
 * POST /api/auth/password — revokes every other session and returns a fresh token pair
 * for this one, exactly like AuthService.changePassword.
 */
export async function changePassword(req: ChangePasswordRequest): Promise<AuthResponse> {
  const res = await api<AuthResponse>('/api/auth/password', { method: 'POST', body: req })
  setSession(res)
  return res
}

/**
 * POST /api/auth/forgot-password → 202 whether or not the address has an account (so the
 * answer never reveals who is registered). The link arrives by email.
 */
export async function requestPasswordReset(email: string): Promise<void> {
  await api<void>('/api/auth/forgot-password', { method: 'POST', body: { email }, auth: false })
}

/**
 * POST /api/auth/reset-password → 204. The server revokes every session of the account, so a
 * session in this browser is dropped too; the user signs in again with the new password.
 */
export async function resetPassword(token: string, newPassword: string): Promise<void> {
  await api<void>('/api/auth/reset-password', { method: 'POST', body: { token, newPassword }, auth: false })
  setSession(null)
}

/** POST /api/auth/verify-email { token } → 204. Works signed in or not. */
export async function verifyEmail(token: string): Promise<void> {
  await api<void>('/api/auth/verify-email', { method: 'POST', body: { token }, auth: false })
}

/** POST /api/auth/verify-email/resend → 202; 429 when asked too often. */
export async function resendVerification(): Promise<void> {
  await api<void>('/api/auth/verify-email/resend', { method: 'POST' })
}

/**
 * POST /api/auth/email → 202. Sends a confirmation link to the new address (and a notice to
 * the current one); nothing changes until the link is used. Wrong password: 400 on currentPassword.
 */
export async function requestEmailChange(newEmail: string, currentPassword: string): Promise<void> {
  await api<void>('/api/auth/email', { method: 'POST', body: { newEmail, currentPassword } })
}

/** POST /api/auth/confirm-email { token } → 204. Works signed in or not. */
export async function confirmEmailChange(token: string): Promise<void> {
  await api<void>('/api/auth/confirm-email', { method: 'POST', body: { token }, auth: false })
}

/**
 * DELETE /api/auth/me { currentPassword } → 204. Cancels any subscription first; a 502 means
 * Stripe could not be reached and nothing was deleted. Clears the local session on success.
 */
export async function deleteAccount(currentPassword: string): Promise<void> {
  await api<void>('/api/auth/me', { method: 'DELETE', body: { currentPassword } })
  setSession(null)
}

/** PATCH /api/auth/me { name } — stored trimmed; the session's copy of the user is updated too. */
export async function updateProfile(req: { name: string }): Promise<User> {
  const user = await api<User>('/api/auth/me', { method: 'PATCH', body: { name: req.name } })
  updateSessionUser(user)
  return user
}

// The billing mock kept accounts here; billing is live now. Clear the leftover copy once.
try {
  localStorage.removeItem('pg-mock-accounts')
} catch {
  // Storage blocked; nothing was stored either.
}

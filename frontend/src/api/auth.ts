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

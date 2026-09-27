/**
 * Auth API — live against AuthController (register, login, refresh via http.ts, logout, me,
 * profile, password). One piece is still local because the backend has no endpoint for it yet:
 * the billing/subscription data other mock modules read through mockAccount().
 */
import type { AuthResponse, ChangePasswordRequest, LoginRequest, RegisterRequest, User } from '../types/auth'
import type { SubscriptionStatus } from '../types/billing'
import { ApiError } from './errors'
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

// --- Local stand-in for data the backend doesn't serve yet (billing, subscriptions) -----------

const STORE_KEY = 'pg-mock-accounts'

/** The signed-in user plus the subscription row the billing mock simulates. */
export interface MockAccount extends User {
  /** Row of `subscriptions`; absent on the Free plan. */
  subscription?: { status: SubscriptionStatus; currentPeriodEnd: string; cancelAtPeriodEnd: boolean }
}

function loadAccounts(): Record<number, MockAccount> {
  try {
    const raw = localStorage.getItem(STORE_KEY)
    if (raw) return JSON.parse(raw) as Record<number, MockAccount>
  } catch {
    // Fall through to empty.
  }
  return {}
}

function saveAccounts(accounts: Record<number, MockAccount>) {
  try {
    localStorage.setItem(STORE_KEY, JSON.stringify(accounts))
  } catch {
    // Local extras just won't survive a reload.
  }
}

/**
 * For the modules still on mock data (billing, status pages, AI): the account behind
 * a user id. Built from the real signed-in user the first time, so those pages work on top of
 * a real login. The plan here is the backend's until the billing mock changes it locally.
 */
export function mockAccount(userId: number): MockAccount {
  const accounts = loadAccounts()
  const existing = accounts[userId]
  const user = getSession()?.user
  // Only plan and subscription are simulated here; name and email always come from the server,
  // or a billing change would write a stale name back into the session.
  if (existing) return user?.id === userId ? { ...existing, name: user.name, email: user.email } : existing
  if (!user || user.id !== userId) throw new ApiError(401, 'Your session has expired. Please sign in again.')
  accounts[userId] = { ...user }
  saveAccounts(accounts)
  return accounts[userId]
}

export function mockUpdateAccount(userId: number, patch: Partial<MockAccount>): User {
  const account = { ...mockAccount(userId), ...patch }
  const accounts = loadAccounts()
  accounts[userId] = account
  saveAccounts(accounts)
  const { subscription: _s, ...user } = account
  return user
}

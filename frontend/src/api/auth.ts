/**
 * Auth API — MOCK implementation of AuthController. See monitors.ts for how to go live.
 *
 * Mock accounts persist in localStorage so a sign-up survives a reload. Passwords are stored
 * in plain text here ONLY because this is a browser-side stand-in; the real backend hashes them.
 */
import type { AuthResponse, ChangePasswordRequest, LoginRequest, RegisterRequest, User } from '../types/auth'
import type { SubscriptionStatus } from '../types/billing'
import { ApiError } from './errors'
import { delay } from './mockDb'
import { getSession, requireUserId, setSession, updateSessionUser } from './session'

const STORE_KEY = 'pg-mock-auth'
const MAX_FAILED_LOGINS = 5
const ACCESS_TOKEN_SECONDS = 900

export interface MockAccount extends User {
  password: string
  /** Row of `subscriptions`; absent on the Free plan. */
  subscription?: { status: SubscriptionStatus; currentPeriodEnd: string; cancelAtPeriodEnd: boolean }
}

interface MockAuthStore {
  accounts: MockAccount[]
  /** access token → user id; logout drops every token for that user. */
  tokens: Record<string, number>
}

export const DEMO_CREDENTIALS = { email: 'dev@acme.io', password: 'password123' }

const seed: MockAuthStore = {
  accounts: [
    {
      id: 1,
      name: 'Demo User',
      email: DEMO_CREDENTIALS.email,
      password: DEMO_CREDENTIALS.password,
      plan: 'PRO',
      role: 'USER',
      createdAt: new Date(Date.now() - 60 * 86_400_000).toISOString(),
      subscription: {
        status: 'active',
        currentPeriodEnd: new Date(Date.now() + 18 * 86_400_000).toISOString(),
        cancelAtPeriodEnd: false,
      },
    },
  ],
  tokens: {},
}

function load(): MockAuthStore {
  try {
    const raw = localStorage.getItem(STORE_KEY)
    if (raw) return JSON.parse(raw) as MockAuthStore
  } catch {
    // Fall through to the seed.
  }
  return structuredClone(seed)
}

function save(store: MockAuthStore) {
  try {
    localStorage.setItem(STORE_KEY, JSON.stringify(store))
  } catch {
    // Accounts just won't survive a reload.
  }
}

// Failed logins per email, like AuthService's attempt limiter (per page load here).
const failedLogins = new Map<string, number>()

const normalize = (email: string) => email.trim().toLowerCase()
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

function toUser({ password: _p, subscription: _s, ...user }: MockAccount): User {
  return user
}

function issueTokens(store: MockAuthStore, account: MockAccount): AuthResponse {
  const token = `mock.${account.id}.${crypto.randomUUID()}`
  store.tokens[token] = account.id
  save(store)
  return {
    token,
    refreshToken: crypto.randomUUID(),
    tokenType: 'Bearer',
    expiresIn: ACCESS_TOKEN_SECONDS,
    user: toUser(account),
  }
}

/** Same checks as the request DTOs' Bean Validation, returned the way the backend returns them. */
function validate(fields: Record<string, string>, rules: Record<string, (v: string) => string | null>) {
  const fieldErrors: Record<string, string> = {}
  for (const [field, rule] of Object.entries(rules)) {
    const error = rule(fields[field] ?? '')
    if (error) fieldErrors[field] = error
  }
  if (Object.keys(fieldErrors).length) throw new ApiError(400, 'Validation failed', fieldErrors)
}

const rules = {
  name: (v: string) => (!v.trim() ? 'Name is required' : v.length > 100 ? 'Name must be 100 characters or fewer' : null),
  email: (v: string) => (!v.trim() ? 'Email is required' : !EMAIL_RE.test(v.trim()) ? 'Email must be valid' : null),
  password: (v: string) => (!v ? 'Password is required' : null),
  newPassword: (v: string) =>
    !v ? 'Password is required' : v.length < 8 || v.length > 72 ? 'Password must be between 8 and 72 characters' : null,
}

/** POST /api/auth/register → 201 */
export async function register(req: RegisterRequest): Promise<AuthResponse> {
  await delay(600)
  validate({ ...req }, { name: rules.name, email: rules.email, password: rules.newPassword })
  const store = load()
  const email = normalize(req.email)
  if (store.accounts.some((a) => a.email === email)) {
    throw new ApiError(409, `Email already registered: ${email}`)
  }
  const account: MockAccount = {
    id: Math.max(0, ...store.accounts.map((a) => a.id)) + 1,
    name: req.name.trim(),
    email,
    password: req.password,
    plan: 'FREE',
    role: 'USER',
    createdAt: new Date().toISOString(),
  }
  store.accounts.push(account)
  const res = issueTokens(store, account)
  setSession(res)
  return res
}

/** POST /api/auth/login */
export async function login(req: LoginRequest): Promise<AuthResponse> {
  await delay(600)
  validate({ ...req }, { email: rules.email, password: rules.password })
  const email = normalize(req.email)
  if ((failedLogins.get(email) ?? 0) >= MAX_FAILED_LOGINS) {
    throw new ApiError(429, 'Too many login attempts. Try again later.')
  }
  const store = load()
  const account = store.accounts.find((a) => a.email === email)
  // Deliberately generic: never reveal whether the email exists.
  if (!account || account.password !== req.password) {
    failedLogins.set(email, (failedLogins.get(email) ?? 0) + 1)
    throw new ApiError(401, 'Invalid email or password')
  }
  failedLogins.delete(email)
  const res = issueTokens(store, account)
  setSession(res)
  return res
}

/** POST /api/auth/logout — ends every session for the caller. */
export async function logout(): Promise<void> {
  const session = getSession()
  setSession(null)
  if (!session) return
  await delay(200)
  const store = load()
  const userId = store.tokens[session.token]
  for (const [token, id] of Object.entries(store.tokens)) {
    if (id === userId) delete store.tokens[token]
  }
  save(store)
}

/** GET /api/auth/me — also how the app checks a stored token is still good on startup. */
export async function me(): Promise<User> {
  await delay(250)
  const session = getSession()
  const store = load()
  const userId = session ? store.tokens[session.token] : undefined
  const account = store.accounts.find((a) => a.id === userId)
  if (!account) {
    setSession(null)
    throw new ApiError(401, 'Your session has expired. Please sign in again.')
  }
  return toUser(account)
}

/** For other mock modules (billing): read and update the stored account behind a user id. */
export function mockAccount(userId: number): MockAccount {
  const account = load().accounts.find((a) => a.id === userId)
  if (!account) throw new ApiError(401, 'Your session has expired. Please sign in again.')
  return account
}

export function mockUpdateAccount(userId: number, patch: Partial<MockAccount>): User {
  const store = load()
  const account = store.accounts.find((a) => a.id === userId)
  if (!account) throw new ApiError(401, 'Your session has expired. Please sign in again.')
  Object.assign(account, patch)
  save(store)
  return toUser(account)
}

/**
 * POST /api/auth/password — revokes every other session and returns a fresh token pair
 * for this one, exactly like AuthService.changePassword.
 */
export async function changePassword(req: ChangePasswordRequest): Promise<AuthResponse> {
  await delay(600)
  validate({ ...req }, { currentPassword: (v) => (!v ? 'Current password is required' : null), newPassword: rules.newPassword })
  const userId = requireUserId()
  const store = load()
  const account = store.accounts.find((a) => a.id === userId)
  if (!account || account.password !== req.currentPassword) throw new ApiError(401, 'Invalid email or password')
  if (req.newPassword === account.password) throw new ApiError(400, 'New password must differ from the current one')
  account.password = req.newPassword
  for (const [token, id] of Object.entries(store.tokens)) {
    if (id === userId) delete store.tokens[token]
  }
  const res = issueTokens(store, account)
  setSession(res)
  return res
}

/** PATCH /api/auth/me { name } — NOT on the backend yet; needed for editing your name. */
export async function updateProfile(req: { name: string }): Promise<User> {
  await delay(400)
  validate({ ...req }, { name: rules.name })
  const user = mockUpdateAccount(requireUserId(), { name: req.name.trim() })
  updateSessionUser(user)
  return user
}

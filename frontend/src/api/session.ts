/**
 * Holds the signed-in session (tokens + user) and persists it across reloads.
 *
 * The backend returns tokens in the response body, so they live in localStorage. That keeps
 * users signed in across tabs and reloads, at the cost of being readable by any script on the
 * page — keep the CSP strict and never render untrusted HTML.
 */
import type { AuthResponse, User } from '../types/auth'
import { ApiError } from './errors'

const KEY = 'pg-session'

export interface Session {
  token: string
  refreshToken: string
  user: User
}

let current: Session | null = read()
const listeners = new Set<(s: Session | null) => void>()

function read(): Session | null {
  try {
    const raw = localStorage.getItem(KEY)
    return raw ? (JSON.parse(raw) as Session) : null
  } catch {
    return null
  }
}

export function getSession(): Session | null {
  return current
}

export function setSession(res: AuthResponse | null): void {
  current = res ? { token: res.token, refreshToken: res.refreshToken, user: res.user } : null
  try {
    if (current) localStorage.setItem(KEY, JSON.stringify(current))
    else localStorage.removeItem(KEY)
  } catch {
    // Storage blocked (private mode etc.) — the session still works until reload.
  }
  listeners.forEach((l) => l(current))
}

export function updateSessionUser(user: User): void {
  if (current) setSession({ ...current, tokenType: 'Bearer', expiresIn: 0, user })
}

/** Called with the new session whenever it changes, including sign-out on a 401. */
export function onSessionChange(listener: (s: Session | null) => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/**
 * The signed-in user's id, as the backend reads it from the JWT. Any API call made without a
 * session fails with 401 and clears local state, which sends the app back to the login page.
 */
export function requireUserId(): number {
  if (!current) {
    setSession(null)
    throw new ApiError(401, 'Your session has expired. Please sign in again.')
  }
  return current.user.id
}

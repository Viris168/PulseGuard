/**
 * The one place that talks to the Spring Boot API. Vite proxies `/api` to localhost:8080
 * (vite.config.ts), so paths stay relative and no CORS setup is needed in development.
 *
 * - Adds `Authorization: Bearer <access token>` from the session.
 * - Turns the backend's error JSON (`{ status, message, fieldErrors }`) into an ApiError.
 * - Access tokens are short-lived (15 min). On a 401 it swaps the refresh token for a new
 *   pair once and retries; if that fails too, the session is cleared and the app returns to
 *   the login page. Concurrent 401s share a single refresh, because the backend rotates the
 *   refresh token on every use: two parallel refreshes would revoke each other.
 */
import type { AuthResponse } from '../types/auth'
import { ApiError } from './errors'
import { getSession, setSession } from './session'

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  /** false for the endpoints that create a session (login, register). */
  auth?: boolean
}

let refreshing: Promise<boolean> | null = null

function refreshSession(): Promise<boolean> {
  const session = getSession()
  if (!session) return Promise.resolve(false)
  refreshing ??= fetch('/api/auth/refresh', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken: session.refreshToken }),
  })
    .then(async (res) => {
      if (!res.ok) return false
      setSession((await res.json()) as AuthResponse)
      return true
    })
    .catch(() => false)
    .finally(() => {
      refreshing = null
    })
  return refreshing
}

async function toApiError(res: Response): Promise<ApiError> {
  try {
    const body = (await res.json()) as { message?: string; fieldErrors?: Record<string, string> }
    return new ApiError(res.status, body.message ?? res.statusText, body.fieldErrors ?? {})
  } catch {
    return new ApiError(res.status, res.statusText || `Request failed (${res.status})`)
  }
}

export async function api<T>(path: string, { method = 'GET', body, auth = true }: RequestOptions = {}): Promise<T> {
  const send = () => {
    const headers: Record<string, string> = {}
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    const token = getSession()?.token
    if (auth && token) headers.Authorization = `Bearer ${token}`
    return fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  }

  let res: Response
  try {
    res = await send()
  } catch {
    throw new ApiError(0, 'Cannot reach the PulseGuard server. Is the backend running?')
  }

  if (res.status === 401 && auth && getSession()) {
    if (await refreshSession()) res = await send()
    if (res.status === 401) {
      setSession(null)
      throw new ApiError(401, 'Your session has expired. Please sign in again.')
    }
  }
  if (!res.ok) throw await toApiError(res)
  if (res.status === 204) return undefined as T
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

/** `?a=1&b=x` from the defined values only; '' when there are none. */
export function queryString(params: Record<string, string | number | undefined | null>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') search.set(key, String(value))
  }
  const s = search.toString()
  return s ? `?${s}` : ''
}

import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthResponse } from '../types/auth'
import { ApiError } from './errors'
import { api } from './http'
import { getSession, setSession } from './session'

const user = { id: 1, name: 'Alice', email: 'a@example.com', plan: 'FREE', role: 'USER', createdAt: '2026-01-01T00:00:00Z', emailVerified: true } as const

function session(token: string, refreshToken = 'refresh-1'): AuthResponse {
  return { token, refreshToken, tokenType: 'Bearer', expiresIn: 900, user }
}

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

let fetchMock: ReturnType<typeof vi.fn>

beforeEach(() => {
  fetchMock = vi.fn()
  vi.stubGlobal('fetch', fetchMock)
  setSession(session('access-1'))
})

const authHeader = (call: number) => (fetchMock.mock.calls[call][1] as RequestInit).headers as Record<string, string>

describe('api()', () => {
  it('sends the access token and parses JSON', async () => {
    fetchMock.mockResolvedValueOnce(json(200, { ok: true }))

    await expect(api('/api/monitors')).resolves.toEqual({ ok: true })
    expect(authHeader(0).Authorization).toBe('Bearer access-1')
  })

  it('never sends a token on public calls', async () => {
    fetchMock.mockResolvedValueOnce(json(200, {}))

    await api('/api/status/acme', { auth: false })

    expect(authHeader(0).Authorization).toBeUndefined()
  })

  it('turns the backend error JSON into an ApiError with field errors', async () => {
    fetchMock.mockResolvedValueOnce(json(400, { status: 400, message: 'Validation failed', fieldErrors: { url: 'URL is required' } }))

    const err = await api('/api/monitors', { method: 'POST', body: {} }).catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ApiError)
    expect(err).toMatchObject({ status: 400, message: 'Validation failed', fieldErrors: { url: 'URL is required' } })
  })

  it('returns undefined for 204', async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }))

    await expect(api('/api/channels/1', { method: 'DELETE' })).resolves.toBeUndefined()
  })

  it('reports an unreachable server clearly', async () => {
    fetchMock.mockRejectedValueOnce(new TypeError('Failed to fetch'))

    await expect(api('/api/monitors')).rejects.toMatchObject({ status: 0 })
  })

  it('refreshes an expired access token once and retries the call', async () => {
    fetchMock
      .mockResolvedValueOnce(json(401, { status: 401, message: 'Authentication required' }))
      .mockResolvedValueOnce(json(200, session('access-2', 'refresh-2')))
      .mockResolvedValueOnce(json(200, { ok: true }))

    await expect(api('/api/monitors')).resolves.toEqual({ ok: true })

    expect(fetchMock.mock.calls[1][0]).toBe('/api/auth/refresh')
    expect(authHeader(2).Authorization).toBe('Bearer access-2')
    expect(getSession()?.refreshToken).toBe('refresh-2')
  })

  it('shares one refresh between concurrent 401s (the server rotates refresh tokens)', async () => {
    let refreshes = 0
    fetchMock.mockImplementation(async (url: string, init: RequestInit) => {
      if (url === '/api/auth/refresh') {
        refreshes++
        return json(200, session('access-2', 'refresh-2'))
      }
      const auth = (init.headers as Record<string, string>).Authorization
      return auth === 'Bearer access-2' ? json(200, { url }) : json(401, { message: 'expired' })
    })

    const results = await Promise.all([api('/api/monitors'), api('/api/incidents'), api('/api/channels')])

    expect(results).toHaveLength(3)
    expect(refreshes).toBe(1)
  })

  it('signs out when the refresh fails too', async () => {
    fetchMock
      .mockResolvedValueOnce(json(401, { message: 'expired' }))
      .mockResolvedValueOnce(json(401, { message: 'refresh expired' }))

    await expect(api('/api/monitors')).rejects.toMatchObject({ status: 401, message: 'Your session has expired. Please sign in again.' })
    expect(getSession()).toBeNull()
  })

  it('leaves a 400 alone: a wrong password must not sign anyone out', async () => {
    fetchMock.mockResolvedValueOnce(json(400, { message: 'Validation failed', fieldErrors: { currentPassword: 'Current password is incorrect' } }))

    await expect(api('/api/auth/password', { method: 'POST', body: {} })).rejects.toMatchObject({ status: 400 })
    expect(getSession()).not.toBeNull()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})

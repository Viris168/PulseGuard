/**
 * Personal API key API — live against ApiKeyController.
 *
 * The server keeps only a SHA-256 of each key; the key itself comes back once, from create.
 * Keys work as `Authorization: Bearer pg_live_…` on the same endpoints as the app, except
 * key management, password change and billing, which need a signed-in session.
 */
import type { ApiKey, CreatedApiKey } from '../types/apiKey'
import { api } from './http'

/** GET /api/api-keys — newest first. */
export async function listApiKeys(): Promise<ApiKey[]> {
  return api<ApiKey[]>('/api/api-keys')
}

/** POST /api/api-keys { name } → 201, the only response that ever contains the key. 409 on a duplicate name. */
export async function createApiKey(name: string): Promise<CreatedApiKey> {
  return api<CreatedApiKey>('/api/api-keys', { method: 'POST', body: { name } })
}

/** DELETE /api/api-keys/{id} → 204. The key stops working on the very next request. */
export async function revokeApiKey(id: number): Promise<void> {
  await api<void>(`/api/api-keys/${id}`, { method: 'DELETE' })
}

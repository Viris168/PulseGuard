/**
 * Personal API key API — MOCK implementation. See monitors.ts for how to go live.
 *
 * Backend needs: api_keys (id, user_id, name, prefix, key_hash, created_at, last_used_at),
 * a filter that accepts `Authorization: Bearer pg_live_…` next to JWTs, and the rule that
 * the plaintext key exists only in the create response. Store a SHA-256 hash (keys are long
 * and random, so a slow hash like BCrypt isn't needed) and compare in constant time.
 */
import type { ApiKey, CreatedApiKey } from '../types/apiKey'
import { ApiError } from './errors'
import { delay } from './mockDb'
import { requireUserId } from './session'

const STORE_KEY = 'pg-mock-api-keys'
const MAX_KEYS = 10
const PREFIX = 'pg_live_'
const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789'

interface StoredKey extends ApiKey {
  userId: number
  hash: string
}

const seed: StoredKey[] = [
  {
    id: 1,
    userId: 1,
    name: 'GitHub Actions deploy',
    prefix: 'pg_live_3fK9',
    // Hash of a key nobody has — the demo can't reveal a secret it never stored.
    hash: '9f2c0b1e6a4d8e3f7a1b5c9d2e6f0a4b8c1d5e9f3a7b0c4d8e2f6a1b5c9d3e7f',
    createdAt: new Date(Date.now() - 20 * 86_400_000).toISOString(),
    lastUsedAt: new Date(Date.now() - 2 * 3_600_000).toISOString(),
  },
]

function load(): StoredKey[] {
  try {
    const raw = localStorage.getItem(STORE_KEY)
    if (raw) return JSON.parse(raw) as StoredKey[]
  } catch {
    // fall through
  }
  return structuredClone(seed)
}

function save(keys: StoredKey[]) {
  try {
    localStorage.setItem(STORE_KEY, JSON.stringify(keys))
  } catch {
    // keys last until reload
  }
}

async function sha256(text: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('')
}

function randomSecret(): string {
  // 32 chars of base62 ≈ 190 bits.
  const bytes = crypto.getRandomValues(new Uint8Array(32))
  return PREFIX + Array.from(bytes, (b) => ALPHABET[b % ALPHABET.length]).join('')
}

const strip = ({ userId: _u, hash: _h, ...k }: StoredKey): ApiKey => ({ ...k })

/** GET /api/api-keys */
export async function listApiKeys(): Promise<ApiKey[]> {
  await delay(300)
  const userId = requireUserId()
  return load()
    .filter((k) => k.userId === userId)
    .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt))
    .map(strip)
}

/** POST /api/api-keys { name } → the only response that ever contains the secret. */
export async function createApiKey(name: string): Promise<CreatedApiKey> {
  await delay(500)
  const userId = requireUserId()
  const trimmed = name.trim()
  if (!trimmed) throw new ApiError(400, 'Validation failed', { name: 'Give the key a name so you know what uses it' })
  if (trimmed.length > 50) throw new ApiError(400, 'Validation failed', { name: 'Keep it under 50 characters' })
  const keys = load()
  const mine = keys.filter((k) => k.userId === userId)
  if (mine.length >= MAX_KEYS) throw new ApiError(400, `You can have up to ${MAX_KEYS} keys. Revoke one you no longer use.`)
  if (mine.some((k) => k.name.toLowerCase() === trimmed.toLowerCase())) {
    throw new ApiError(409, 'Validation failed', { name: 'You already have a key with that name' })
  }

  const secret = randomSecret()
  const stored: StoredKey = {
    id: Math.max(0, ...keys.map((k) => k.id)) + 1,
    userId,
    name: trimmed,
    prefix: secret.slice(0, PREFIX.length + 4),
    hash: await sha256(secret),
    createdAt: new Date().toISOString(),
    lastUsedAt: null,
  }
  save([...keys, stored])
  return { apiKey: strip(stored), secret }
}

/** DELETE /api/api-keys/{id} — takes effect on the very next request. */
export async function revokeApiKey(id: number): Promise<void> {
  await delay(300)
  const userId = requireUserId()
  const keys = load()
  if (!keys.some((k) => k.id === id && k.userId === userId)) throw new ApiError(404, 'API key not found')
  save(keys.filter((k) => k.id !== id))
}

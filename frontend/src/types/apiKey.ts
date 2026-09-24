// GET/POST /api/api-keys, DELETE /api/api-keys/{id} — not on the backend yet.
export interface ApiKey {
  id: number
  name: string
  /** First characters of the key, safe to show (e.g. "pg_live_3fK9"). */
  prefix: string
  createdAt: string
  lastUsedAt: string | null
}

/** Only returned once, from the create call. The server keeps just a hash. */
export interface CreatedApiKey {
  apiKey: ApiKey
  secret: string
}

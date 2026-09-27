/**
 * Status page API — live against StatusPageController (owner) and PublicStatusPageController.
 *
 * The public data lives at /api/status/{slug} rather than /status/{slug}, because the SPA
 * serves the page itself at /status/:slug. Slug rules, ownership of the monitors and the
 * "address taken" check all run on the server; SLUG_RE and slugify only help the form.
 */
import type { PublicStatusPage, StatusPageConfig } from '../types/statusPage'
import { api } from './http'

export const SLUG_RE = /^[a-z0-9](?:[a-z0-9-]{1,38}[a-z0-9])$/

export function slugify(s: string): string {
  return s
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 40)
}

/** GET /api/status-page — 204 (null) until the user saves one. */
export async function getMyStatusPage(): Promise<StatusPageConfig | null> {
  return (await api<StatusPageConfig | undefined>('/api/status-page')) ?? null
}

/** PUT /api/status-page — create or replace. 409 with a slug field error when the address is taken. */
export async function saveStatusPage(req: StatusPageConfig): Promise<StatusPageConfig> {
  return api<StatusPageConfig>('/api/status-page', { method: 'PUT', body: req })
}

/** GET /api/status/{slug} — public; sent without a token so a stale session can't get in the way. */
export async function getPublicStatusPage(slug: string): Promise<PublicStatusPage> {
  return api<PublicStatusPage>(`/api/status/${encodeURIComponent(slug)}`, { auth: false })
}

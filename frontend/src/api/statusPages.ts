/**
 * Status page API — MOCK implementation. See monitors.ts for how to go live.
 *
 * Backend needs (none exist yet):
 *   status_pages (id, user_id, slug UNIQUE, title, description, published, created_at)
 *   status_page_monitors (status_page_id, monitor_id, display_name, position)
 *   GET/PUT /api/status-page            — owner, authenticated
 *   GET     /api/status/{slug}           — public: add to SecurityConfig's permitAll and cache it.
 *     (architecture.md says /status/{slug}; with the SPA serving /status/:slug, the JSON needs
 *      its own path under /api.)
 *
 * Configs persist in localStorage so a page opened in a new tab sees your edits.
 */
import type { MonitorState } from '../types/monitor'
import type { ComponentStatus, OverallStatus, PublicStatusPage, StatusPageConfig } from '../types/statusPage'
import { limitsFor } from '../lib/plans'
import { mockAccount } from './auth'
import { ApiError } from './errors'
import { db, delay, ownMonitors } from './mockDb'
import { dailyUptime } from './mockHistory'
import { requireUserId } from './session'

const STORE_KEY = 'pg-mock-status-pages'
const MAX_HISTORY_DAYS = 90
const INCIDENT_WINDOW_DAYS = 14

interface StoredPage extends StatusPageConfig {
  userId: number
}

const seed: StoredPage[] = [
  {
    userId: 1,
    slug: 'acme',
    title: 'Acme Status',
    description: 'Live status of Acme’s API, website and payments.',
    published: true,
    monitors: [
      { monitorId: 1, displayName: 'Public API' },
      { monitorId: 4, displayName: 'Sign-in' },
      { monitorId: 3, displayName: 'Payments' },
      { monitorId: 5, displayName: 'Search' },
      { monitorId: 2, displayName: 'Website' },
    ],
  },
]

function load(): StoredPage[] {
  try {
    const raw = localStorage.getItem(STORE_KEY)
    if (raw) return JSON.parse(raw) as StoredPage[]
  } catch {
    // fall through
  }
  return structuredClone(seed)
}

function save(pages: StoredPage[]) {
  try {
    localStorage.setItem(STORE_KEY, JSON.stringify(pages))
  } catch {
    // edits last until reload
  }
}

const RESERVED = new Set(['admin', 'api', 'app', 'login', 'signup', 'status', 'www', 'pulseguard'])
export const SLUG_RE = /^[a-z0-9](?:[a-z0-9-]{1,38}[a-z0-9])$/

export function slugify(s: string): string {
  return s
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 40)
}

const strip = ({ userId: _u, ...c }: StoredPage): StatusPageConfig => structuredClone(c)

/** GET /api/status-page — null until the user creates one. */
export async function getMyStatusPage(): Promise<StatusPageConfig | null> {
  await delay(300)
  const userId = requireUserId()
  const page = load().find((p) => p.userId === userId)
  return page ? strip(page) : null
}

/** PUT /api/status-page — create or replace. */
export async function saveStatusPage(req: StatusPageConfig): Promise<StatusPageConfig> {
  await delay(500)
  const userId = requireUserId()
  const fieldErrors: Record<string, string> = {}
  const slug = req.slug.trim().toLowerCase()
  if (!SLUG_RE.test(slug)) fieldErrors.slug = 'Use 3–40 lowercase letters, numbers or dashes'
  else if (RESERVED.has(slug)) fieldErrors.slug = 'That address is reserved'
  if (!req.title.trim()) fieldErrors.title = 'Title is required'
  else if (req.title.length > 80) fieldErrors.title = 'Keep it under 80 characters'
  if (req.description.length > 280) fieldErrors.description = 'Keep it under 280 characters'
  const owned = new Set(ownMonitors().map((m) => m.id))
  if (req.monitors.some((m) => !owned.has(m.monitorId))) throw new ApiError(404, 'Monitor not found')
  if (req.monitors.some((m) => !m.displayName.trim())) fieldErrors.monitors = 'Every monitor needs a display name'
  if (Object.keys(fieldErrors).length) throw new ApiError(400, 'Validation failed', fieldErrors)

  const pages = load()
  if (pages.some((p) => p.slug === slug && p.userId !== userId)) {
    throw new ApiError(409, 'That address is taken', { slug: 'That address is already taken' })
  }
  const stored: StoredPage = {
    userId,
    slug,
    title: req.title.trim(),
    description: req.description.trim(),
    published: req.published,
    monitors: req.monitors.map((m) => ({ monitorId: m.monitorId, displayName: m.displayName.trim() })),
  }
  save([...pages.filter((p) => p.userId !== userId), stored])
  return strip(stored)
}

const PUBLIC_STATUS: Record<MonitorState, ComponentStatus> = {
  UP: 'OPERATIONAL',
  // Unconfirmed: the incident engine hasn't opened anything, so the public shouldn't see an alarm.
  SUSPICIOUS: 'OPERATIONAL',
  DOWN: 'OUTAGE',
  RECOVERING: 'RECOVERING',
}

/** GET /api/status/{slug} — public, no session. */
export async function getPublicStatusPage(slug: string): Promise<PublicStatusPage> {
  await delay(400)
  const page = load().find((p) => p.slug === slug.toLowerCase())
  // Unpublished pages are indistinguishable from missing ones.
  if (!page || !page.published) throw new ApiError(404, 'Status page not found')

  const historyDays = Math.min(MAX_HISTORY_DAYS, limitsFor(mockAccount(page.userId).plan).retentionDays)
  const byId = new Map(db.monitors.filter((m) => m.userId === page.userId).map((m) => [m.id, m]))
  const entries = page.monitors.flatMap((pm) => {
    const m = byId.get(pm.monitorId)
    return m ? [{ pm, m }] : [] // deleted monitors just drop off
  })

  const components = entries.map(({ pm, m }) => {
    const { buckets, uptimePct } = dailyUptime(m, db.incidents, historyDays)
    return {
      name: pm.displayName,
      status: m.isActive ? PUBLIC_STATUS[m.state] : ('PAUSED' as const),
      uptimePct,
      days: buckets.map((b) => ({ date: b.start, uptimePct: b.uptimePct, downtimeSeconds: b.downtimeSeconds })),
    }
  })

  const live = components.filter((c) => c.status !== 'PAUSED')
  const outages = live.filter((c) => c.status === 'OUTAGE').length
  const overall: OverallStatus =
    outages && outages === live.length
      ? 'MAJOR_OUTAGE'
      : outages
        ? 'PARTIAL_OUTAGE'
        : live.some((c) => c.status === 'RECOVERING')
          ? 'DEGRADED'
          : 'OPERATIONAL'

  const since = Date.now() - INCIDENT_WINDOW_DAYS * 86_400_000
  const names = new Map(entries.map(({ pm, m }) => [m.id, pm.displayName]))
  const states = new Map(entries.map(({ m }) => [m.id, m.state]))
  const incidents = db.incidents
    .filter((i) => names.has(i.monitorId) && (!i.resolvedAt || Date.parse(i.resolvedAt) >= since))
    .sort((a, b) => Date.parse(b.startedAt) - Date.parse(a.startedAt))
    .map((i) => ({
      id: i.id,
      componentName: names.get(i.monitorId)!,
      status: i.status,
      recovering: i.status === 'OPEN' && states.get(i.monitorId) === 'RECOVERING',
      startedAt: i.startedAt,
      resolvedAt: i.resolvedAt,
    }))

  return {
    title: page.title,
    description: page.description,
    overall,
    historyDays,
    components,
    incidents,
    generatedAt: new Date().toISOString(),
  }
}

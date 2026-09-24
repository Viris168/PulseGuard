import type { IncidentStatus } from './incident'

// ── Owner side: GET/PUT /api/status-page (not in architecture.md yet) ──

export interface StatusPageMonitor {
  monitorId: number
  /** Shown publicly instead of the monitor's own name. URLs are never shown. */
  displayName: string
}

export interface StatusPageConfig {
  slug: string
  title: string
  description: string
  published: boolean
  /** In display order. */
  monitors: StatusPageMonitor[]
}

// ── Public side: GET /api/status/{slug} — no auth, sanitised, cacheable ──

export type OverallStatus = 'OPERATIONAL' | 'DEGRADED' | 'PARTIAL_OUTAGE' | 'MAJOR_OUTAGE'
/** Public wording of MonitorState. SUSPICIOUS is unconfirmed, so it shows as operational. */
export type ComponentStatus = 'OPERATIONAL' | 'RECOVERING' | 'OUTAGE' | 'PAUSED'

export interface PublicDay {
  date: string
  uptimePct: number | null
  downtimeSeconds: number
}

export interface PublicComponent {
  name: string
  status: ComponentStatus
  uptimePct: number | null
  days: PublicDay[]
}

export interface PublicIncident {
  id: number
  componentName: string
  status: IncidentStatus
  /** Open, but checks are passing again (monitor RECOVERING). */
  recovering: boolean
  startedAt: string
  resolvedAt: string | null
}

export interface PublicStatusPage {
  title: string
  description: string
  overall: OverallStatus
  /** Follows the owner's plan retention (7 days on Free, 90 on paid plans). */
  historyDays: number
  components: PublicComponent[]
  /** Last 14 days, newest first. Causes are left out on purpose. */
  incidents: PublicIncident[]
  generatedAt: string
}

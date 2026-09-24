/**
 * Incident API — MOCK implementation. See monitors.ts for how to go live.
 */
import type { Incident, IncidentDetail, IncidentStatus } from '../types/incident'
import { ApiError } from './errors'
import { delay, findMonitor, ownIncidents, ownMonitors } from './mockDb'
import { buildTimeline } from './mockHistory'

interface IncidentFilter {
  status?: IncidentStatus
  /** Not in architecture.md yet — the backend needs `?monitorId=` for the monitor detail page. */
  monitorId?: number
}

/** GET /api/incidents?status=&monitorId=  (newest first) */
export async function listIncidents(filter: IncidentFilter = {}): Promise<Incident[]> {
  await delay()
  const names = new Map(ownMonitors().map((m) => [m.id, m.name]))
  return ownIncidents()
    .filter((i) => (filter.status ? i.status === filter.status : true))
    .filter((i) => (filter.monitorId !== undefined ? i.monitorId === filter.monitorId : true))
    .sort((a, b) => Date.parse(b.startedAt) - Date.parse(a.startedAt))
    .map((i) => ({ ...i, monitorName: names.get(i.monitorId) ?? 'Deleted monitor' }))
}

/** GET /api/incidents/{id} */
export async function getIncident(id: number): Promise<IncidentDetail> {
  await delay()
  const incident = ownIncidents().find((i) => i.id === id)
  // Scoped by user on the backend: someone else's incident is a 404 too.
  if (!incident) throw new ApiError(404, 'Incident not found')
  const m = findMonitor(incident.monitorId)
  return {
    ...structuredClone(incident),
    monitorName: m.name,
    monitorType: m.type,
    monitorUrl: m.url,
    monitorMethod: m.method,
    intervalSeconds: m.intervalSeconds,
    timeline: buildTimeline(m, incident),
  }
}

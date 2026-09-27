/**
 * Incident API — live against IncidentController.
 */
import type { Incident, IncidentDetail, IncidentStatus } from '../types/incident'
import { api, queryString } from './http'

interface IncidentFilter {
  status?: IncidentStatus
  monitorId?: number
}

/** GET /api/incidents?status=&monitorId=  (newest first, scoped to the caller on the server) */
export async function listIncidents(filter: IncidentFilter = {}): Promise<Incident[]> {
  return api<Incident[]>(`/api/incidents${queryString({ status: filter.status, monitorId: filter.monitorId })}`)
}

/** GET /api/incidents/{id} — the incident plus its timeline; another account's incident is a 404. */
export async function getIncident(id: number): Promise<IncidentDetail> {
  return api<IncidentDetail>(`/api/incidents/${id}`)
}

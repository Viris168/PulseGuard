/**
 * In-memory stand-in for the backend database, shared by every mock API module.
 * Lives until the page reloads.
 */
import { ApiError } from './errors'
import { requireUserId } from './session'
import { seedIncidents, seedMonitors, type MockIncident, type MockMonitor } from './mockData'

export const db = {
  monitors: structuredClone(seedMonitors) as MockMonitor[],
  incidents: structuredClone(seedIncidents) as MockIncident[],
  nextMonitorId: Math.max(...seedMonitors.map((m) => m.id)) + 1,
}

export const delay = (ms = 350) => new Promise((r) => setTimeout(r, ms))

/** The signed-in user's monitors. */
export function ownMonitors(): MockMonitor[] {
  const userId = requireUserId()
  return db.monitors.filter((m) => m.userId === userId)
}

/** The signed-in user's incidents (via monitor ownership). */
export function ownIncidents(): MockIncident[] {
  const ids = new Set(ownMonitors().map((m) => m.id))
  return db.incidents.filter((i) => ids.has(i.monitorId))
}

export function findMonitor(id: number): MockMonitor {
  const m = ownMonitors().find((x) => x.id === id)
  // Backend returns 404 for another tenant's monitor too.
  if (!m) throw new ApiError(404, 'Monitor not found')
  return m
}

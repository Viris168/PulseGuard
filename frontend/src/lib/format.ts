export function cn(...classes: (string | false | null | undefined)[]): string {
  return classes.filter(Boolean).join(' ')
}

export function timeAgo(iso: string | null): string {
  if (!iso) return 'Never'
  const seconds = Math.round((Date.now() - new Date(iso).getTime()) / 1000)
  if (seconds < 45) return 'just now'
  const minutes = Math.round(seconds / 60)
  if (minutes < 60) return `${minutes}m ago`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours}h ago`
  return `${Math.round(hours / 24)}d ago`
}

export function formatInterval(seconds: number): string {
  if (seconds % 3600 === 0) return `${seconds / 3600}h`
  if (seconds % 60 === 0) return `${seconds / 60}m`
  return `${seconds}s`
}

export function formatUptime(pct: number | null): string {
  if (pct === null) return '—'
  return pct === 100 ? '100%' : `${pct.toFixed(2)}%`
}

export function formatMs(ms: number | null): string {
  if (ms === null) return '—'
  return ms >= 1000 ? `${(ms / 1000).toFixed(2)}s` : `${ms}ms`
}

/** 90 → "1m 30s", 7680 → "2h 8m", 273600 → "3d 4h". Drops zero parts and seconds past an hour. */
export function formatDuration(seconds: number): string {
  if (seconds < 60) return `${Math.round(seconds)}s`
  const d = Math.floor(seconds / 86_400)
  const h = Math.floor((seconds % 86_400) / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = Math.round(seconds % 60)
  if (d) return h ? `${d}d ${h}h` : `${d}d`
  if (h) return m ? `${h}h ${m}m` : `${h}h`
  return s ? `${m}m ${s}s` : `${m}m`
}

const timeFmt = new Intl.DateTimeFormat(undefined, { hour: '2-digit', minute: '2-digit' })
const dayFmt = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric' })
const dateTimeFmt = new Intl.DateTimeFormat(undefined, {
  month: 'short',
  day: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
})

export const formatTime = (iso: string) => timeFmt.format(new Date(iso))
export const formatDay = (iso: string) => dayFmt.format(new Date(iso))
export const formatDateTime = (iso: string) => dateTimeFmt.format(new Date(iso))

/** "Sep 24, 14:00 – 14:15" when both ends share a day, otherwise both ends in full. */
export function formatSpan(startIso: string, endIso: string): string {
  const sameDay = new Date(startIso).toDateString() === new Date(endIso).toDateString()
  return `${formatDateTime(startIso)} – ${sameDay ? formatTime(endIso) : formatDateTime(endIso)}`
}

export function formatCount(n: number): string {
  return new Intl.NumberFormat(undefined, { notation: n >= 10_000 ? 'compact' : 'standard' }).format(n)
}

/** How long an incident lasted, or has lasted so far if still open. */
export function incidentDurationSeconds(i: { startedAt: string; resolvedAt: string | null }): number {
  return ((i.resolvedAt ? Date.parse(i.resolvedAt) : Date.now()) - Date.parse(i.startedAt)) / 1000
}

/** Where heartbeat jobs send pings. Set VITE_PING_BASE_URL per environment. */
export const PING_BASE_URL: string = import.meta.env.VITE_PING_BASE_URL ?? 'https://pulseguard.com'

/** Where a heartbeat stands against its schedule: due soon, in grace, or overdue. */
export function heartbeatDue(m: {
  lastCheckedAt: string | null
  intervalSeconds: number
  graceSeconds: number | null
}): { text: string; tone: 'ok' | 'late' | 'overdue' | 'waiting' } {
  if (!m.lastCheckedAt) return { text: 'Waiting for first ping', tone: 'waiting' }
  const due = Date.parse(m.lastCheckedAt) + m.intervalSeconds * 1000
  const late = (Date.now() - due) / 1000
  if (late <= 0) return { text: `Due in ${formatDuration(-late)}`, tone: 'ok' }
  if (late <= (m.graceSeconds ?? 0)) return { text: `Late ${formatDuration(late)} (in grace)`, tone: 'late' }
  return { text: `Overdue ${formatDuration(late)}`, tone: 'overdue' }
}

/** "every 1h (+10m grace)" */
export function heartbeatSchedule(m: { intervalSeconds: number; graceSeconds: number | null }): string {
  return `every ${formatDuration(m.intervalSeconds)}${m.graceSeconds ? ` (+${formatDuration(m.graceSeconds)} grace)` : ''}`
}

/** Public API origin shown in examples. Set VITE_API_BASE_URL per environment. */
export const API_BASE_URL: string = import.meta.env.VITE_API_BASE_URL ?? 'https://pulseguard.com'

// Mirrors com.viris.PulseGuard.enumeration.IncidentStatus
export type IncidentStatus = 'OPEN' | 'RESOLVED'

// Mirrors ChannelType / NotificationEventType / NotificationStatus
export type ChannelType = 'EMAIL' | 'SLACK' | 'TELEGRAM' | 'SMS' | 'WEBHOOK'
export type NotificationEventType = 'OPENED' | 'RESOLVED'
export type NotificationStatus = 'SENT' | 'FAILED'

// One row of the `incidents` table, plus the monitor name for display.
export interface Incident {
  id: number
  monitorId: number
  monitorName: string
  status: IncidentStatus
  cause: string | null
  /** Time of the first failed check in the streak that opened this incident. */
  startedAt: string
  resolvedAt: string | null
}

export type IncidentEvent =
  | { type: 'CHECK_FAILED'; at: string; detail: string }
  | { type: 'OPENED'; at: string; failedChecks: number }
  | {
      type: 'NOTIFIED'
      at: string
      event: NotificationEventType
      channel: ChannelType
      target: string
      status: NotificationStatus
      error?: string
    }
  | { type: 'CHECK_PASSED'; at: string }
  | { type: 'RESOLVED'; at: string; passedChecks: number }

// GET /api/incidents/{id} — the row plus a timeline built from checks + the `notifications` table.
export interface IncidentDetail extends Incident {
  monitorType: 'HTTP' | 'HEARTBEAT'
  monitorUrl: string
  monitorMethod: string
  intervalSeconds: number
  /** Oldest first. */
  timeline: IncidentEvent[]
}

// Row of `notification_channels` — GET/POST/DELETE /api/channels
export interface NotificationChannel {
  id: number
  type: ChannelType
  /** Email address, Slack webhook URL, phone number… depending on type. */
  target: string
  enabled: boolean
  createdAt: string
}

export interface ChannelRequest {
  type: ChannelType
  target: string
}

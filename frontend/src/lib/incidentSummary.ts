/**
 * The incident summary that needs no AI: always available, instant and free. The AI-written one
 * (api/incidents.ts getIncidentSummary) replaces it when the backend has a model configured.
 */
import type { IncidentDetail } from '../types/incident'
import { formatDateTime, formatDuration, formatTime, incidentDurationSeconds } from './format'

/** Plain-English meanings of the status codes monitors report most. */
const STATUS_CODES: Record<number, string> = {
  200: 'OK — the request worked.',
  201: 'Created — the request worked and made something new.',
  204: 'No Content — it worked, and there is deliberately no body.',
  301: 'Moved Permanently — the URL has a new home. Point the monitor at the new URL.',
  302: 'Found (temporary redirect) — often a login page. Check the monitor isn’t hitting an auth wall.',
  304: 'Not Modified — the cached copy is still valid.',
  400: 'Bad Request — the server didn’t understand the request. Check the method and body.',
  401: 'Unauthorized — credentials are missing or wrong.',
  403: 'Forbidden — the server understood but refuses. Often a firewall, WAF or IP allow-list.',
  404: 'Not Found — nothing lives at that path. A typo, or a route removed in a deploy.',
  405: 'Method Not Allowed — try GET or HEAD for health checks.',
  408: 'Request Timeout — the server gave up waiting for the request.',
  429: 'Too Many Requests — you’re being rate limited. Check less often or allow-list PulseGuard.',
  500: 'Internal Server Error — the app crashed while handling the request. Check its logs around that time.',
  502: 'Bad Gateway — a proxy or load balancer couldn’t get a valid answer from the app behind it. Usually the app is down or restarting.',
  503: 'Service Unavailable — the server is up but refusing work: overloaded, in maintenance, or a dependency (database, cache) is down.',
  504: 'Gateway Timeout — the proxy waited too long for the app. Look for slow queries or a hung process.',
  522: 'Connection Timed Out (Cloudflare) — Cloudflare couldn’t reach your origin server.',
  524: 'A Timeout Occurred (Cloudflare) — your origin accepted the connection but took too long to answer.',
}

export function explainCause(cause: string): string {
  const code = /got (\d{3})/.exec(cause)?.[1]
  if (code && STATUS_CODES[Number(code)]) return `HTTP ${code} means ${STATUS_CODES[Number(code)].replace(/^[^—]+— /, '')}`
  if (/dns/i.test(cause)) return 'DNS failed: the hostname didn’t resolve. Check the domain’s DNS records or whether it expired.'
  if (/ssl|tls|certificate/i.test(cause)) return 'The TLS certificate was rejected. Renew it and check auto-renewal is running.'
  if (/timed out/i.test(cause)) return 'The server didn’t answer within the timeout. It may be overloaded or stuck.'
  if (/refused|reset/i.test(cause)) return 'The connection was refused or reset: nothing was listening, or it crashed mid-request.'
  if (/no ping/i.test(cause)) return 'The job didn’t check in. It may have crashed, hung, or not been scheduled.'
  return ''
}

/**
 * A summary built from the incident's own data, no model involved. Shown when AI summaries are
 * off or the provider fails (GET /api/incidents/{id}/summary answers 204), so the card is never empty.
 */
export function ruleBasedSummary(i: IncidentDetail): string {
  const heartbeat = i.monitorType === 'HEARTBEAT'
  const d = formatDuration(incidentDurationSeconds(i))
  const alerts = i.timeline.filter((e) => e.type === 'NOTIFIED' && e.event === 'OPENED')
  const failed = alerts.filter((e) => e.type === 'NOTIFIED' && e.status === 'FAILED')
  const opened = i.timeline.find((e) => e.type === 'OPENED')
  const channelNames = [...new Set(alerts.map((e) => (e.type === 'NOTIFIED' ? (e.channel === 'EMAIL' ? 'email' : e.channel === 'SLACK' ? 'Slack' : e.channel) : '')))]

  const parts = [
    i.status === 'OPEN'
      ? `${i.monitorName} has been down for ${d}.`
      : `${i.monitorName} was down for ${d} on ${formatDateTime(i.startedAt)}.`,
    [i.cause ? `${i.cause}.` : '', i.cause ? explainCause(i.cause) : ''].filter(Boolean).join(' '),
    opened
      ? `PulseGuard confirmed it ${heartbeat ? 'when the grace period ran out' : 'after 3 failed checks'} at ${formatTime(opened.at)} and alerted ${channelNames.join(' and ') || 'nobody'}.`
      : '',
    failed.length ? `The ${failed.map((e) => (e.type === 'NOTIFIED' ? LABELS[e.channel] : '')).join(' and ')} alert failed to deliver — check that channel's settings.` : '',
    i.resolvedAt ? `It recovered at ${formatTime(i.resolvedAt)}.` : heartbeat ? 'It resolves on the next ping.' : 'It resolves after 2 passing checks in a row.',
  ]
  return parts.filter(Boolean).join(' ')
}

const LABELS: Record<string, string> = { EMAIL: 'email', SLACK: 'Slack', SMS: 'SMS', TELEGRAM: 'Telegram', WEBHOOK: 'webhook' }

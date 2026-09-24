/**
 * Ask AI — MOCK implementation (see ROADMAP §4).
 *
 * Production: POST /api/ai/ask { question } → AiAssistantService loads *this user's* monitors and
 * recent failures (scoped by principal.getUserId(), never chosen by the model), sends them with
 * the question to the LLM via Spring AI ChatClient, and returns the answer. The API key lives only
 * on the backend. Here, a rule-based stand-in answers from the same mock data so the UI is real.
 */
import type { Plan } from '../types/auth'
import type { IncidentDetail } from '../types/incident'
import { formatDateTime, formatDuration, formatMs, formatTime, formatUptime, heartbeatDue, incidentDurationSeconds } from '../lib/format'
import { mockAccount } from './auth'
import { ApiError } from './errors'
import type { MockIncident, MockMonitor } from './mockData'
import { db, delay, ownIncidents, ownMonitors } from './mockDb'
import { computeStats } from './mockHistory'
import { requireUserId } from './session'

export interface AiLink {
  label: string
  to: string
}

export interface AiAnswer {
  /** Plain text. Lines starting with "- " are bullets; **text** is bold. */
  answer: string
  links: AiLink[]
  quota: AiQuota
}

export interface AiQuota {
  used: number
  /** null = unlimited */
  limit: number | null
}

// Roadmap: "Rate limit per plan (e.g. Free 5/day) → natural Pro feature."
export const AI_DAILY_LIMIT: Record<Plan, number | null> = { FREE: 5, PRO: 100, BUSINESS: null }

const USAGE_KEY = 'pg-mock-ai-usage'
const today = () => new Date().toISOString().slice(0, 10)

function readUsage(userId: number): number {
  try {
    const all = JSON.parse(localStorage.getItem(USAGE_KEY) ?? '{}') as Record<string, { date: string; count: number }>
    const u = all[userId]
    return u && u.date === today() ? u.count : 0
  } catch {
    return 0
  }
}

function writeUsage(userId: number, count: number) {
  try {
    const all = JSON.parse(localStorage.getItem(USAGE_KEY) ?? '{}') as Record<string, unknown>
    all[userId] = { date: today(), count }
    localStorage.setItem(USAGE_KEY, JSON.stringify(all))
  } catch {
    // Usage resets on reload; fine for a mock.
  }
}

// ─── Access: which monitors Ask AI may read ────────────────────────────────

/** PUT/GET /api/ai/access — the user's consent and scope. Off until they turn it on. */
export interface AiAccess {
  enabled: boolean
  /** Includes monitors added later. */
  allMonitors: boolean
  monitorIds: number[]
}

const ACCESS_KEY = 'pg-mock-ai-access'

function readAccess(userId: number): AiAccess {
  try {
    const all = JSON.parse(localStorage.getItem(ACCESS_KEY) ?? '{}') as Record<string, AiAccess>
    return all[userId] ?? { enabled: false, allMonitors: true, monitorIds: [] }
  } catch {
    return { enabled: false, allMonitors: true, monitorIds: [] }
  }
}

export async function getAiAccess(): Promise<AiAccess> {
  await delay(200)
  return readAccess(requireUserId())
}

export async function saveAiAccess(access: AiAccess): Promise<AiAccess> {
  await delay(500)
  const userId = requireUserId()
  const owned = new Set(ownMonitors().map((m) => m.id))
  const monitorIds = access.monitorIds.filter((id) => owned.has(id))
  if (access.enabled && !access.allMonitors && monitorIds.length === 0) {
    throw new ApiError(400, 'Select at least one monitor')
  }
  const saved = { ...access, monitorIds }
  try {
    const all = JSON.parse(localStorage.getItem(ACCESS_KEY) ?? '{}') as Record<string, AiAccess>
    all[userId] = saved
    localStorage.setItem(ACCESS_KEY, JSON.stringify(all))
  } catch {
    // lasts until reload
  }
  return saved
}

/** GET /api/ai/quota */
export async function getAiQuota(): Promise<AiQuota> {
  const userId = requireUserId()
  return { used: readUsage(userId), limit: AI_DAILY_LIMIT[mockAccount(userId).plan] }
}

/** POST /api/ai/ask { question } */
export async function askAi(question: string): Promise<AiAnswer> {
  const userId = requireUserId()
  const q = question.trim()
  if (!q) throw new ApiError(400, 'Validation failed', { question: 'Ask a question' })
  if (q.length > 500) throw new ApiError(400, 'Validation failed', { question: 'Keep questions under 500 characters' })
  const access = readAccess(userId)
  if (!access.enabled) throw new ApiError(403, 'Turn on Ask AI first.')
  const plan = mockAccount(userId).plan
  const limit = AI_DAILY_LIMIT[plan]
  const used = readUsage(userId)
  if (limit !== null && used >= limit) {
    throw new ApiError(429, `You've used all ${limit} questions for today on the ${plan === 'FREE' ? 'Free' : 'Pro'} plan.`)
  }
  // Models take a moment; so does the mock, so the loading state is visible.
  await delay(900 + Math.min(900, q.length * 8))
  writeUsage(userId, used + 1)
  // Scope, enforced server-side: the model only ever sees monitors the user allowed.
  const all = ownMonitors()
  const allowedIds = new Set(access.allMonitors ? all.map((m) => m.id) : access.monitorIds)
  const monitors = all.filter((m) => allowedIds.has(m.id))
  const hidden = all.filter((m) => !allowedIds.has(m.id))
  const incidents = ownIncidents().filter((i) => allowedIds.has(i.monitorId))
  const { answer, links } = answerFrom(q.toLowerCase(), monitors, incidents, hidden)
  return { answer, links, quota: { used: used + 1, limit } }
}

// ─── The rule-based stand-in ────────────────────────────────────────────────

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

function explainCause(cause: string): string {
  const code = /got (\d{3})/.exec(cause)?.[1]
  if (code && STATUS_CODES[Number(code)]) return `HTTP ${code} means ${STATUS_CODES[Number(code)].replace(/^[^—]+— /, '')}`
  if (/dns/i.test(cause)) return 'DNS failed: the hostname didn’t resolve. Check the domain’s DNS records or whether it expired.'
  if (/ssl|tls|certificate/i.test(cause)) return 'The TLS certificate was rejected. Renew it and check auto-renewal is running.'
  if (/timed out/i.test(cause)) return 'The server didn’t answer within the timeout. It may be overloaded or stuck.'
  if (/refused|reset/i.test(cause)) return 'The connection was refused or reset: nothing was listening, or it crashed mid-request.'
  if (/no ping/i.test(cause)) return 'The job didn’t check in. It may have crashed, hung, or not been scheduled.'
  return ''
}

const monitorLink = (m: MockMonitor): AiLink => ({ label: `Open ${m.name}`, to: `/monitors/${m.id}` })

/** A monitor the question names, by full name or a distinctive word from it. */
function findMentioned(q: string, monitors: MockMonitor[]): MockMonitor | null {
  const full = monitors.find((m) => q.includes(m.name.toLowerCase()))
  if (full) return full
  const common = new Set(['api', 'the', 'site', 'service', 'job', 'my', 'and'])
  return (
    monitors.find((m) =>
      m.name
        .toLowerCase()
        .split(/\W+/)
        .some((w) => w.length > 2 && !common.has(w) && new RegExp(`\\b${w}`).test(q)),
    ) ?? null
  )
}

function answerFrom(
  q: string,
  monitors: MockMonitor[],
  incidents: MockIncident[],
  hidden: MockMonitor[],
): { answer: string; links: AiLink[] } {
  const byId = new Map(monitors.map((m) => [m.id, m]))

  // "What does 502 mean?"
  const code = /\b([1-5]\d\d)\b/.exec(q)?.[1]
  if (code && (STATUS_CODES[Number(code)] || /mean|code|status|error/.test(q))) {
    const text = STATUS_CODES[Number(code)] ?? 'That isn’t a standard status code, so it depends on the server.'
    const seen = incidents.filter((i) => i.cause.includes(code))
    return {
      answer:
        `**HTTP ${code}**: ${text}` +
        (seen.length
          ? `\n\nYou've seen it on **${byId.get(seen[0].monitorId)?.name}** (${seen.length} incident${seen.length > 1 ? 's' : ''}).`
          : ''),
      links: seen.length ? [{ label: 'See that incident', to: `/incidents/${seen[0].id}` }] : [],
    }
  }

  const mentioned = findMentioned(q, monitors)
  const outOfScope = !mentioned && findMentioned(q, hidden)
  if (outOfScope) {
    return {
      answer: `I don't have access to **${outOfScope.name}**. You can add it in Ask AI access settings.`,
      links: [],
    }
  }

  // "Why did Payments go down last night?"
  if (mentioned && /why|down|fail|happen|incident|outage|wrong|broke|error|problem|issue/.test(q)) {
    const mine = incidents
      .filter((i) => i.monitorId === mentioned.id)
      .sort((a, b) => Number(b.status === 'OPEN') - Number(a.status === 'OPEN') || Date.parse(b.startedAt) - Date.parse(a.startedAt))
    const stats = computeStats(mentioned, db.incidents, '30d')
    if (!mine.length) {
      return {
        answer: `**${mentioned.name}** has had no incidents in the last 30 days. Uptime is **${formatUptime(stats.uptimePct)}**.`,
        links: [monitorLink(mentioned)],
      }
    }
    const i = mine[0]
    const d = formatDuration(incidentDurationSeconds(i))
    const lead =
      i.status === 'OPEN'
        ? `**${mentioned.name}** has been down for **${d}**, since ${formatTime(i.startedAt)}.`
        : `**${mentioned.name}** was last down on ${formatDateTime(i.startedAt)} for **${d}**.`
    const why = explainCause(i.cause)
    const history = mine.length > 1 ? `\n\nIt's had **${mine.length} incidents** recently; 30-day uptime is ${formatUptime(stats.uptimePct)}.` : ''
    return {
      answer: `${lead}\n\nCause: ${i.cause}.${why ? ` ${why}` : ''}${history}`,
      links: [{ label: 'Open the incident', to: `/incidents/${i.id}` }, monitorLink(mentioned)],
    }
  }

  // "Which monitor is slowest this week?"
  if (/slow|fast|latency|response time|quick/.test(q)) {
    const ranked = monitors
      .filter((m) => m.type === 'HTTP' && m.isActive)
      .map((m) => ({ m, avg: computeStats(m, db.incidents, '7d').avgResponseMs }))
      .filter((x): x is { m: MockMonitor; avg: number } => x.avg !== null)
      .sort((a, b) => (/fast|quick/.test(q) ? a.avg - b.avg : b.avg - a.avg))
    if (!ranked.length) return { answer: 'There isn’t enough response-time data yet. Check back after a few checks have run.', links: [] }
    const top = ranked[0]
    return {
      answer:
        `The ${/fast|quick/.test(q) ? 'fastest' : 'slowest'} this week is **${top.m.name}** at **${formatMs(top.avg)}** on average.\n\n` +
        ranked
          .slice(0, 5)
          .map((x) => `- ${x.m.name}: ${formatMs(x.avg)}`)
          .join('\n'),
      links: [monitorLink(top.m)],
    }
  }

  // "Is anything down?"
  if (/down|outage|broken|wrong|status|issue|problem|right now|healthy|working/.test(q) && !mentioned) {
    const issues = monitors.filter((m) => m.isActive && m.lastCheckedAt && m.state !== 'UP')
    const overdue = monitors.filter((m) => m.type === 'HEARTBEAT' && m.isActive && heartbeatDue(m).tone === 'overdue' && m.state === 'UP')
    const all = [...issues, ...overdue]
    if (!all.length) return { answer: 'Everything is up. No monitors are failing right now.', links: [{ label: 'Open monitors', to: '/monitors' }] }
    const label: Record<string, string> = { DOWN: 'down', SUSPICIOUS: 'failing checks, not confirmed yet', RECOVERING: 'recovering', UP: 'overdue' }
    return {
      answer: `${all.length} monitor${all.length > 1 ? 's need' : ' needs'} attention:\n\n` + all.map((m) => `- **${m.name}**: ${label[m.state]}`).join('\n'),
      links: all.slice(0, 2).map(monitorLink),
    }
  }

  // "What's my uptime this month?"
  if (/uptime|reliab|sla|availability/.test(q)) {
    const rows = monitors
      .filter((m) => m.isActive)
      .map((m) => ({ m, up: computeStats(m, db.incidents, '30d').uptimePct }))
      .filter((x): x is { m: MockMonitor; up: number } => x.up !== null)
      .sort((a, b) => a.up - b.up)
    const below = rows.filter((r) => r.up < 99.9)
    return {
      answer:
        `30-day uptime, worst first:\n\n` +
        rows.map((r) => `- ${r.m.name}: ${formatUptime(r.up)}`).join('\n') +
        (below.length ? `\n\n**${below.length}** ${below.length > 1 ? 'are' : 'is'} below 99.9%.` : '\n\nAll above 99.9%. Nice.'),
      links: below[0] ? [monitorLink(below[0].m)] : [],
    }
  }

  // "Any incidents this week?"
  if (/incident|outage|this week|recent|last/.test(q)) {
    const week = incidents.filter((i) => Date.parse(i.startedAt) > Date.now() - 7 * 86_400_000)
    if (!week.length) return { answer: 'No incidents in the last 7 days.', links: [] }
    return {
      answer:
        `${week.length} incident${week.length > 1 ? 's' : ''} in the last 7 days:\n\n` +
        week.map((i) => `- **${byId.get(i.monitorId)?.name}**: ${i.cause} (${formatDuration(incidentDurationSeconds(i))}${i.status === 'OPEN' ? ', ongoing' : ''})`).join('\n'),
      links: [{ label: 'Open incidents', to: '/incidents' }],
    }
  }

  // "How do heartbeats work?"
  if (/heartbeat|cron|ping|job/.test(q)) {
    const hb = monitors.filter((m) => m.type === 'HEARTBEAT')
    return {
      answer:
        'A heartbeat flips monitoring around: your cron job calls a secret ping URL when it finishes. If no ping arrives by the expected time plus the grace period, the monitor goes down and you get an alert.' +
        (hb.length ? `\n\nYour heartbeats:\n\n${hb.map((m) => `- **${m.name}**: ${heartbeatDue(m).text.toLowerCase()}`).join('\n')}` : ''),
      links: hb.length ? [monitorLink(hb[0])] : [{ label: 'Create a heartbeat', to: '/monitors/new' }],
    }
  }

  return {
    answer:
      "I can answer questions about your monitors, incidents and HTTP errors. Try:\n\n- Why did Payments webhook go down?\n- Which monitor is slowest this week?\n- What does 503 mean?\n- What's my uptime this month?",
    links: [],
  }
}

/** GET /api/incidents/{id}/summary — the roadmap's cheaper first AI feature (one call per incident). */
export async function summarizeIncident(i: IncidentDetail): Promise<string> {
  await delay(600)
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

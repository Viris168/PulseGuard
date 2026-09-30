/**
 * Ask AI — live against AskAiController. Each question goes to the backend, which sends it to the
 * AI model with a snapshot of *this user's* monitors (only the ones they shared), and returns the
 * answer. The model and its API key live only on the backend.
 */
import { api } from './http'

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

/** GET/PUT /api/ai/access — the user's consent and scope. Off until they turn it on. */
export interface AiAccess {
  enabled: boolean
  /** Includes monitors added later. */
  allMonitors: boolean
  monitorIds: number[]
}

export async function getAiAccess(): Promise<AiAccess> {
  return api<AiAccess>('/api/ai/access')
}

/** Monitor ids the user doesn't own are dropped by the server. */
export async function saveAiAccess(access: AiAccess): Promise<AiAccess> {
  return api<AiAccess>('/api/ai/access', { method: 'PUT', body: access })
}

/** GET /api/ai/quota — questions asked today and the plan's daily limit. */
export async function getAiQuota(): Promise<AiQuota> {
  return api<AiQuota>('/api/ai/quota')
}

/**
 * POST /api/ai/ask. Sends the browser's time zone so answers name times the way the dashboard
 * shows them. 403 when Ask AI is off, 429 at the daily limit, 503 when the model couldn't answer
 * (that question isn't counted).
 */
export async function askAi(question: string): Promise<AiAnswer> {
  return api<AiAnswer>('/api/ai/ask', { method: 'POST', body: { question: question.trim(), timeZone: browserTimeZone() } })
}

function browserTimeZone(): string | undefined {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone
  } catch {
    return undefined
  }
}

// The Ask AI mock kept usage and access here; both live on the server now. Clear the leftovers once.
try {
  localStorage.removeItem('pg-mock-ai-usage')
  localStorage.removeItem('pg-mock-ai-access')
} catch {
  // Storage blocked; nothing was stored either.
}

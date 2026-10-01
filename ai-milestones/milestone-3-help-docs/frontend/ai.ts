/**
 * Ask AI — live against AskAiController (access, quota) and ChatController (conversations).
 * Each question goes to the backend, which sends it to the AI model with the conversation so far
 * and a snapshot of *this user's* shared monitors, and streams the answer back. The model and its
 * API key live only on the backend.
 */
import { api, apiFetch } from './http'

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

// ─── Conversations ───────────────────────────────────────────────────────────

/** Mirrors ConversationResponse. */
export interface AiConversation {
  id: number
  title: string
  createdAt: string
  updatedAt: string
  /** The page the chat was started from: a monitor, or an incident (then its monitor too). */
  contextMonitorId: number | null
  contextIncidentId: number | null
}

export type MessageStatus = 'COMPLETE' | 'PARTIAL' | 'FAILED'

/** A help-doc section an answer is based on, e.g. "Slack alerts › Setting it up" at /docs/slack-alerts#setting-it-up. */
export interface HelpSource {
  title: string
  url: string
}

/** Mirrors MessageResponse. Answers are plain text: lines starting "- " are bullets, **text** is bold. */
export interface AiMessage {
  id: number
  role: 'USER' | 'ASSISTANT'
  content: string
  status: MessageStatus
  createdAt: string
  /** The caller's thumbs up (1) or down (-1) on an answer; null when not rated. */
  rating: 1 | -1 | null
  /** What the model looked up for an answer, e.g. "Checked uptime for Health, 2026-09-01". */
  lookups: string[]
  /** The help-doc sections the answer can cite; [1] in the text is the first. Empty for data answers. */
  sources: HelpSource[]
}

/** GET /api/ai/conversations — newest first. */
export async function listConversations(): Promise<AiConversation[]> {
  return api<AiConversation[]>('/api/ai/conversations')
}

/**
 * POST /api/ai/conversations — an empty chat; its first question becomes the title. With a
 * monitor or incident, the chat is about that page (404 when it isn't yours or isn't shared).
 */
export async function createConversation(about?: { monitorId?: number; incidentId?: number }): Promise<AiConversation> {
  return api<AiConversation>('/api/ai/conversations', { method: 'POST', body: about })
}

/** GET /api/ai/conversations/{id}/messages — oldest first. Another account's chat is a 404. */
export async function getMessages(conversationId: number): Promise<AiMessage[]> {
  return api<AiMessage[]>(`/api/ai/conversations/${conversationId}/messages`)
}

export async function renameConversation(conversationId: number, title: string): Promise<AiConversation> {
  return api<AiConversation>(`/api/ai/conversations/${conversationId}`, { method: 'PATCH', body: { title } })
}

export async function deleteConversation(conversationId: number): Promise<void> {
  return api<void>(`/api/ai/conversations/${conversationId}`, { method: 'DELETE' })
}

/** PUT /api/ai/messages/{id}/feedback — rating again replaces the earlier rating. */
export async function rateMessage(messageId: number, rating: 1 | -1): Promise<void> {
  return api<void>(`/api/ai/messages/${messageId}/feedback`, { method: 'PUT', body: { rating } })
}

// ─── Streaming an answer ─────────────────────────────────────────────────────

/** `event: done` — the answer is saved. */
export interface StreamDone {
  questionId: number
  answerId: number
  status: MessageStatus
  quota: AiQuota
}

/** `event: error` — the answer ended early. `counted: false` means the question was handed back. */
export interface StreamError {
  message: string
  counted: boolean
  /** The saved partial answer, when some text came before the error. */
  answerId: number | null
}

export type StreamResult =
  | { kind: 'done'; done: StreamDone }
  | { kind: 'error'; error: StreamError }
  /** The person pressed Stop: the server keeps what was written as a PARTIAL answer. */
  | { kind: 'stopped' }

/**
 * POST /api/ai/conversations/{id}/messages, reading the Server-Sent Events answer with fetch
 * (EventSource can't send the Authorization header). Calls `onDelta` with each piece as it
 * arrives, and `onTool` with each lookup the model makes ("Checked uptime for Health, …") and,
 * for a help-docs search, the sections it found. Refusals (403, 404, 409, 429) happen before the stream starts and throw ApiError,
 * like any other call. Aborting `signal` is Stop.
 */
export async function streamMessage(
  conversationId: number,
  question: string,
  {
    onDelta,
    onTool,
    signal,
  }: { onDelta: (text: string) => void; onTool?: (label: string, sources: HelpSource[]) => void; signal?: AbortSignal },
): Promise<StreamResult> {
  let result: StreamResult | null = null
  try {
    const res = await apiFetch(`/api/ai/conversations/${conversationId}/messages`, {
      method: 'POST',
      body: { question: question.trim(), timeZone: browserTimeZone() },
      signal,
    })
    if (!res.body) throw new Error('The answer stream could not be read.')
    const parser = createSseParser((event, data) => {
      if (event === 'delta') onDelta((JSON.parse(data) as { text: string }).text)
      else if (event === 'tool') {
        const tool = JSON.parse(data) as { label: string; sources?: HelpSource[] }
        onTool?.(tool.label, tool.sources ?? [])
      }
      else if (event === 'done') result = { kind: 'done', done: JSON.parse(data) as StreamDone }
      else if (event === 'error') result = { kind: 'error', error: JSON.parse(data) as StreamError }
    })
    const reader = res.body.getReader()
    const decoder = new TextDecoder()
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      parser.push(decoder.decode(value, { stream: true }))
    }
    parser.push(decoder.decode())
    parser.end()
  } catch (e) {
    if (signal?.aborted) return { kind: 'stopped' }
    throw e
  }
  return result ?? { kind: 'error', error: { message: 'The answer ended unexpectedly. Try again.', counted: true, answerId: null } }
}

/**
 * Turns Server-Sent Events text into (event, data) calls. Text arrives in network-sized chunks
 * that can split a line or an event anywhere, so it buffers until a blank line ends an event.
 * Spring writes `event:delta` without a space after the colon; the SSE format allows one, so
 * both are accepted.
 */
export function createSseParser(onEvent: (event: string, data: string) => void) {
  let buffer = ''
  let event = 'message'
  let data: string[] = []

  const dispatch = () => {
    if (data.length) onEvent(event, data.join('\n'))
    event = 'message'
    data = []
  }

  const line = (raw: string) => {
    const text = raw.endsWith('\r') ? raw.slice(0, -1) : raw
    if (text === '') return dispatch()
    if (text.startsWith(':')) return // a comment, e.g. a keep-alive
    const colon = text.indexOf(':')
    const field = colon === -1 ? text : text.slice(0, colon)
    let value = colon === -1 ? '' : text.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'event') event = value
    else if (field === 'data') data.push(value)
  }

  return {
    push(chunk: string) {
      buffer += chunk
      let newline = buffer.indexOf('\n')
      while (newline !== -1) {
        line(buffer.slice(0, newline))
        buffer = buffer.slice(newline + 1)
        newline = buffer.indexOf('\n')
      }
    },
    /** The stream closed: a last event without its blank line still counts. */
    end() {
      if (buffer) line(buffer)
      buffer = ''
      dispatch()
    },
  }
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

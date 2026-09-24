/**
 * Alert channel API — MOCK implementation. See monitors.ts for how to go live.
 * Plan gating mirrors PlanLimits.allowsChannel on the backend.
 */
import type { ChannelRequest, ChannelType, NotificationChannel } from '../types/incident'
import { limitsFor } from '../lib/plans'
import { mockAccount } from './auth'
import { ApiError } from './errors'
import { delay } from './mockDb'
import { requireUserId } from './session'

interface MockChannel extends NotificationChannel {
  userId: number
}

let channels: MockChannel[] = []
let nextId = 1
// Users whose default channels exist. The backend would create the email channel at sign-up.
const initialized = new Set<number>()

function own(): MockChannel[] {
  const userId = requireUserId()
  if (!initialized.has(userId)) {
    initialized.add(userId)
    const account = mockAccount(userId)
    const now = new Date().toISOString()
    channels.push({ id: nextId++, userId, type: 'EMAIL', target: account.email, enabled: true, createdAt: account.createdAt })
    if (userId === 1) channels.push({ id: nextId++, userId, type: 'SLACK', target: 'https://hooks.slack.com/services/T000/B000/ops-alerts', enabled: true, createdAt: now })
  }
  return channels.filter((c) => c.userId === userId)
}

const strip = ({ userId: _u, ...c }: MockChannel): NotificationChannel => ({ ...c })

export const CHANNEL_RULES: Partial<Record<ChannelType, { test: (v: string) => boolean; message: string }>> = {
  EMAIL: { test: (v) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v), message: 'Enter a valid email address' },
  SLACK: {
    test: (v) => /^https:\/\/hooks\.slack\.com\/services\/\S+$/.test(v),
    message: 'Paste a Slack incoming webhook URL (https://hooks.slack.com/services/…)',
  },
  SMS: { test: (v) => /^\+[1-9]\d{7,14}$/.test(v.replace(/[\s-]/g, '')), message: 'Use international format, e.g. +85512345678' },
}

/** GET /api/channels */
export async function listChannels(): Promise<NotificationChannel[]> {
  await delay(300)
  return own().map(strip)
}

/** POST /api/channels */
export async function createChannel(req: ChannelRequest): Promise<NotificationChannel> {
  await delay(500)
  const userId = requireUserId()
  const plan = mockAccount(userId).plan
  if (!limitsFor(plan).channels.includes(req.type)) {
    throw new ApiError(403, `The ${plan} plan does not include ${req.type} alerts.`)
  }
  const target = req.type === 'SMS' ? req.target.replace(/[\s-]/g, '') : req.target.trim()
  const rule = CHANNEL_RULES[req.type]
  if (!target) throw new ApiError(400, 'Validation failed', { target: 'Target is required' })
  if (rule && !rule.test(target)) throw new ApiError(400, 'Validation failed', { target: rule.message })
  if (own().some((c) => c.type === req.type && c.target === target)) {
    throw new ApiError(409, 'That channel already exists.')
  }
  const created: MockChannel = { id: nextId++, userId, type: req.type, target, enabled: true, createdAt: new Date().toISOString() }
  channels.push(created)
  return strip(created)
}

/** PATCH /api/channels/{id} { enabled } — NOT in architecture.md yet (only GET/POST/DELETE). */
export async function setChannelEnabled(id: number, enabled: boolean): Promise<NotificationChannel> {
  await delay(250)
  const c = own().find((x) => x.id === id)
  if (!c) throw new ApiError(404, 'Channel not found')
  c.enabled = enabled
  return strip(c)
}

/** DELETE /api/channels/{id} */
export async function deleteChannel(id: number): Promise<void> {
  await delay(300)
  const mine = own()
  if (!mine.some((c) => c.id === id)) throw new ApiError(404, 'Channel not found')
  if (mine.length === 1) throw new ApiError(400, 'Keep at least one channel so you hear about outages.')
  channels = channels.filter((c) => c.id !== id)
}

/** POST /api/channels/{id}/test — NOT in architecture.md yet; sends a sample alert. */
export async function sendTestAlert(id: number): Promise<void> {
  await delay(900)
  const c = own().find((x) => x.id === id)
  if (!c) throw new ApiError(404, 'Channel not found')
  if (!limitsFor(mockAccount(c.userId).plan).channels.includes(c.type)) {
    throw new ApiError(403, `Your plan does not include ${c.type} alerts.`)
  }
}

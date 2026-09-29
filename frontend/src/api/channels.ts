/**
 * Alert channel API — live against ChannelController.
 *
 * Plan gating, target validation and duplicate checks all run on the server; CHANNEL_RULES
 * only gives the form instant feedback. Saved webhook URLs come back masked (TargetMasker):
 * the server never echoes a secret it already has.
 */
import type { ChannelRequest, ChannelType, NotificationChannel } from '../types/incident'
import { api } from './http'

export const CHANNEL_RULES: Partial<Record<ChannelType, { test: (v: string) => boolean; message: string }>> = {
  EMAIL: { test: (v) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v), message: 'Enter a valid email address' },
  SLACK: {
    test: (v) => /^https:\/\/hooks\.slack\.com\/services\/\S+$/.test(v),
    message: 'Paste a Slack incoming webhook URL (https://hooks.slack.com/services/…)',
  },
  SMS: { test: (v) => /^\+[1-9]\d{7,14}$/.test(v.replace(/[\s-]/g, '')), message: 'Use international format, e.g. +85512345678' },
}

/** GET /api/channels — oldest first; the sign-up email channel is always there. */
export async function listChannels(): Promise<NotificationChannel[]> {
  return api<NotificationChannel[]>('/api/channels')
}

/** POST /api/channels → 201. 403 when the plan lacks the type, 409 for a duplicate. */
export async function createChannel(req: ChannelRequest): Promise<NotificationChannel> {
  return api<NotificationChannel>('/api/channels', { method: 'POST', body: req })
}

/** PATCH /api/channels/{id} { enabled } — re-enabling a type the plan no longer includes is a 403. */
export async function setChannelEnabled(id: number, enabled: boolean): Promise<NotificationChannel> {
  return api<NotificationChannel>(`/api/channels/${id}`, { method: 'PATCH', body: { enabled } })
}

/** DELETE /api/channels/{id} → 204. The last channel can't be deleted (400). */
export async function deleteChannel(id: number): Promise<void> {
  await api<void>(`/api/channels/${id}`, { method: 'DELETE' })
}

/** POST /api/channels/{id}/resend-confirmation → 202. For an email channel still awaiting confirmation. */
export async function resendChannelConfirmation(id: number): Promise<void> {
  await api<void>(`/api/channels/${id}/resend-confirmation`, { method: 'POST' })
}

/** POST /api/channels/confirm { token } → 204. From the link emailed to the channel's address; no session needed. */
export async function confirmChannel(token: string): Promise<void> {
  await api<void>('/api/channels/confirm', { method: 'POST', body: { token }, auth: false })
}

/** POST /api/channels/{id}/test → 204. Sends for real; throttled per user (429). */
export async function sendTestAlert(id: number): Promise<void> {
  await api<void>(`/api/channels/${id}/test`, { method: 'POST' })
}

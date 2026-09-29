import type { APIRequestContext } from '@playwright/test'

const MAILPIT = process.env.E2E_MAILPIT_URL ?? 'http://localhost:8025'

interface Summary {
  ID: string
  Subject: string
  To: { Address: string }[]
}

/**
 * Waits for an email with this subject to this address, and returns the first link in it
 * that points at the app (the verification, reset or confirmation link).
 */
export async function linkFromEmail(request: APIRequestContext, to: string, subject: string): Promise<string> {
  const deadline = Date.now() + 20_000
  while (Date.now() < deadline) {
    const res = await request.get(`${MAILPIT}/api/v1/search`, { params: { query: `to:"${to}" subject:"${subject}"` } })
    const { messages } = (await res.json()) as { messages: Summary[] }
    if (messages.length) {
      const message = await (await request.get(`${MAILPIT}/api/v1/message/${messages[0].ID}`)).json()
      const link = /(https?:\/\/[^\s]+\?token=[A-Za-z0-9_-]+)/.exec(message.Text as string)
      if (!link) throw new Error(`No link in "${subject}" to ${to}`)
      return link[1]
    }
    await new Promise((r) => setTimeout(r, 500))
  }
  throw new Error(`No "${subject}" email to ${to} within 20s`)
}

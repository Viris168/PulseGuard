import { ApiError } from '../../api/errors'

export const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

/** Splits an API failure into per-field messages (400) and one banner message (everything else). */
export function readApiError(err: unknown): { fields: Record<string, string>; banner: string | null } {
  if (err instanceof ApiError) {
    if (Object.keys(err.fieldErrors).length) return { fields: err.fieldErrors, banner: null }
    return { fields: {}, banner: err.message }
  }
  return { fields: {}, banner: "Couldn't reach PulseGuard. Check your connection and try again." }
}

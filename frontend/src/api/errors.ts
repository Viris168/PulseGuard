/** Mirrors common/exception/ApiError: `{ status, message, fieldErrors, timestamp }`. */
export class ApiError extends Error {
  readonly status: number
  /** Bean Validation failures keyed by request field, e.g. `{ email: 'Email must be valid' }`. */
  readonly fieldErrors: Record<string, string>

  constructor(status: number, message: string, fieldErrors: Record<string, string> = {}) {
    super(message)
    this.status = status
    this.fieldErrors = fieldErrors
  }
}

/** A user-facing explanation for a failed load; 404 covers both deleted and other tenants' records. */
export function loadErrorMessage(err: unknown, thing: string): string {
  if (err instanceof ApiError && err.status === 404) {
    return `This ${thing} may have been deleted, or it belongs to another account.`
  }
  return err instanceof Error ? err.message : `Failed to load ${thing}`
}

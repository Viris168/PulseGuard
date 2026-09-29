import { execSync } from 'node:child_process'

/**
 * Clears the dev stack's rate-limit counters before a run: login attempts (login:*) and
 * account emails (account-email:*: reset, verification, email change). They cap each IP per
 * window, and every run comes from this machine, so without this the journey could only run
 * a few times before "Too many attempts". Only the local Docker Redis is touched; point
 * E2E_REDIS_CONTAINER elsewhere, or set it to "none" to skip.
 */
const PATTERNS = ['login:*', 'account-email:*']

export default function globalSetup() {
  const container = process.env.E2E_REDIS_CONTAINER ?? 'pulseguard-redis-1'
  if (container === 'none') return
  try {
    for (const pattern of PATTERNS) {
      execSync(`docker exec ${container} sh -c "redis-cli --scan --pattern '${pattern}' | xargs -r redis-cli DEL"`, {
        stdio: 'ignore',
      })
    }
  } catch {
    console.warn(`e2e: could not clear rate limits in ${container}; a run may hit "Too many requests"`)
  }
}

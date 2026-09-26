import type { Plan } from './auth'

// Stripe subscription statuses the app cares about (`subscriptions.status`).
export type SubscriptionStatus = 'active' | 'past_due' | 'canceled'

// GET /api/billing/subscription — not in architecture.md yet; the billing page needs it.
export interface BillingSummary {
  plan: Plan
  /** null on the Free plan (no Stripe subscription). */
  status: SubscriptionStatus | null
  currentPeriodEnd: string | null
  /** Set when the user cancelled in the Stripe portal; the plan drops to Free at period end. */
  cancelAtPeriodEnd: boolean
  usage: { monitors: number }
}

// POST /api/billing/checkout and /api/billing/portal both answer with a Stripe-hosted URL.
export interface RedirectResponse {
  url: string
}

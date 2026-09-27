import type { Plan } from './auth'

// The Stripe statuses GET /api/billing/subscription reports: only ones that keep the paid plan
// (enumeration/SubscriptionStatus.grantsPlan). An ended subscription is reported as null.
export type SubscriptionStatus = 'trialing' | 'active' | 'past_due'

// Mirrors billing/dto/BillingSummaryResponse
export interface BillingSummary {
  plan: Plan
  /** null on the Free plan (never subscribed, or the subscription ended). */
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

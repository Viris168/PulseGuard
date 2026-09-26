/**
 * Billing API — MOCK implementation. See monitors.ts for how to go live.
 *
 * Production flow (architecture.md §3.8):
 *   upgrade   → POST /api/billing/checkout → redirect to Stripe Checkout → webhook updates the plan
 *   downgrade / cancel / card / invoices → POST /api/billing/portal → Stripe Customer Portal
 * The mock applies the webhook's effect immediately and "redirects" back to /billing.
 */
import type { Plan } from '../types/auth'
import type { BillingSummary, RedirectResponse } from '../types/billing'
import { PLAN_ORDER } from '../lib/plans'
import { mockAccount, mockUpdateAccount } from './auth'
import { ApiError } from './errors'
import { delay, ownMonitors } from './mockDb'
import { requireUserId, updateSessionUser } from './session'

const BILLING_PERIOD_MS = 30 * 86_400_000

/** GET /api/billing/subscription */
export async function getBillingSummary(): Promise<BillingSummary> {
  await delay(250)
  const account = mockAccount(requireUserId())
  const sub = account.subscription
  return {
    plan: account.plan,
    status: account.plan === 'FREE' ? null : (sub?.status ?? 'active'),
    currentPeriodEnd: sub?.currentPeriodEnd ?? null,
    cancelAtPeriodEnd: sub?.cancelAtPeriodEnd ?? false,
    usage: { monitors: ownMonitors().length },
  }
}

/** POST /api/billing/checkout { plan } → Stripe Checkout URL. */
export async function startCheckout(plan: Exclude<Plan, 'FREE'>): Promise<RedirectResponse> {
  await delay(700)
  const userId = requireUserId()
  const account = mockAccount(userId)
  if (PLAN_ORDER[plan] <= PLAN_ORDER[account.plan]) {
    throw new ApiError(400, `You're already on the ${account.plan} plan or higher.`)
  }
  // What the checkout.session.completed webhook would do once payment succeeds.
  const user = mockUpdateAccount(userId, {
    plan,
    subscription: {
      status: 'active',
      currentPeriodEnd: new Date(Date.now() + BILLING_PERIOD_MS).toISOString(),
      cancelAtPeriodEnd: false,
    },
  })
  updateSessionUser(user)
  return { url: `/billing?checkout=success&plan=${plan}` }
}

/** POST /api/billing/portal → Stripe Customer Portal URL. */
export async function openPortal(): Promise<RedirectResponse> {
  await delay(400)
  requireUserId()
  return { url: '/billing?portal=mock' }
}

/**
 * MOCK ONLY — stands in for what the user does inside the Stripe portal, plus the
 * customer.subscription.updated webhook that follows.
 *   to FREE  → cancel at period end (keeps paid features until then)
 *   to PRO   → switch now (Stripe prorates)
 */
export async function mockPortalChangePlan(plan: Plan): Promise<void> {
  await delay(600)
  const userId = requireUserId()
  const account = mockAccount(userId)
  const sub = account.subscription ?? {
    status: 'active' as const,
    currentPeriodEnd: new Date(Date.now() + BILLING_PERIOD_MS).toISOString(),
    cancelAtPeriodEnd: false,
  }
  const user =
    plan === 'FREE'
      ? mockUpdateAccount(userId, { subscription: { ...sub, cancelAtPeriodEnd: true } })
      : mockUpdateAccount(userId, { plan, subscription: { ...sub, cancelAtPeriodEnd: false } })
  updateSessionUser(user)
}

/** MOCK ONLY — "Renew" in the portal: undo a pending cancellation. */
export async function mockPortalResume(): Promise<void> {
  await delay(500)
  const userId = requireUserId()
  const sub = mockAccount(userId).subscription
  if (sub) mockUpdateAccount(userId, { subscription: { ...sub, cancelAtPeriodEnd: false } })
}

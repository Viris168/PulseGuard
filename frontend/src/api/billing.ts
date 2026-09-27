/**
 * Billing API — live against BillingController.
 *
 *   upgrade from Free                     → POST /api/billing/checkout → Stripe Checkout
 *   switch plan / cancel / card / invoices → POST /api/billing/portal   → Stripe Customer Portal
 *
 * Neither changes the plan by itself. Stripe's webhook does, a moment after the user finishes
 * on Stripe, so the billing page polls the summary when it comes back from Checkout.
 */
import type { Plan } from '../types/auth'
import type { BillingSummary, RedirectResponse } from '../types/billing'
import { api } from './http'

/** GET /api/billing/subscription */
export function getBillingSummary(): Promise<BillingSummary> {
  return api<BillingSummary>('/api/billing/subscription')
}

/** POST /api/billing/checkout { plan } → Stripe Checkout URL. 409 when already subscribed. */
export function startCheckout(plan: Exclude<Plan, 'FREE'>): Promise<RedirectResponse> {
  return api<RedirectResponse>('/api/billing/checkout', { method: 'POST', body: { plan } })
}

/** POST /api/billing/portal → Stripe Customer Portal URL. 409 before the first checkout. */
export function openPortal(): Promise<RedirectResponse> {
  return api<RedirectResponse>('/api/billing/portal', { method: 'POST' })
}

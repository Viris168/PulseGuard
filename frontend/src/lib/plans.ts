import type { Plan } from '../types/auth'
import type { ChannelType } from '../types/incident'

export interface PlanInfo {
  plan: Plan
  name: string
  /** USD per month. Placeholder — the PRD only gives ranges ($9–15 Pro, $30–50 Business). */
  price: number
  tagline: string
  /** Mirrors billing/PlanLimits on the backend, which is what actually enforces these. */
  limits: {
    maxMonitors: number
    minIntervalSeconds: number
    retentionDays: number
    channels: ChannelType[]
  }
}

export const PLANS: PlanInfo[] = [
  {
    plan: 'FREE',
    name: 'Free',
    price: 0,
    tagline: 'For side projects and trying things out',
    limits: { maxMonitors: 3, minIntervalSeconds: 300, retentionDays: 7, channels: ['EMAIL'] },
  },
  {
    plan: 'PRO',
    name: 'Pro',
    price: 12,
    tagline: 'For products with real users',
    limits: { maxMonitors: 25, minIntervalSeconds: 60, retentionDays: 90, channels: ['EMAIL', 'SLACK'] },
  },
  {
    plan: 'BUSINESS',
    name: 'Business',
    price: 39,
    tagline: 'For teams that promise uptime to customers',
    limits: { maxMonitors: Infinity, minIntervalSeconds: 60, retentionDays: 365, channels: ['EMAIL', 'SLACK', 'SMS'] },
  },
]

export const PLAN_ORDER: Record<Plan, number> = { FREE: 0, PRO: 1, BUSINESS: 2 }

export function planInfo(plan: Plan): PlanInfo {
  return PLANS.find((p) => p.plan === plan)!
}

export const limitsFor = (plan: Plan) => planInfo(plan).limits

/** The cheapest plan that would lift a limit, for upgrade prompts. */
export function nextPlan(plan: Plan): PlanInfo | null {
  return PLANS.find((p) => PLAN_ORDER[p.plan] === PLAN_ORDER[plan] + 1) ?? null
}

export const formatMonitorLimit = (n: number) => (n === Infinity ? 'Unlimited' : String(n))

import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/errors'
import type { PublicStatusPage as PublicPage } from '../types/statusPage'
import { billing, heartbeatMonitor, httpMonitor, user, withStats } from '../test/fixtures'
import { BillingPage } from './BillingPage'
import { StatusPageEditor } from './StatusPageEditor'
import { PublicStatusPage } from './public/PublicStatusPage'

const billingApi = vi.hoisted(() => ({ getBillingSummary: vi.fn(), startCheckout: vi.fn(), openPortal: vi.fn() }))
const statusApi = vi.hoisted(() => ({ getMyStatusPage: vi.fn(), saveStatusPage: vi.fn(), getPublicStatusPage: vi.fn() }))
const auth = vi.hoisted(() => ({ user: { current: null as ReturnType<typeof user> | null } }))
vi.mock('../api/billing', () => billingApi)
vi.mock('../api/statusPages', async (importActual) => ({
  ...(await importActual<typeof import('../api/statusPages')>()),
  ...statusApi,
}))
vi.mock('../api/monitors', () => ({
  listMonitors: vi.fn().mockResolvedValue([withStats(httpMonitor()), withStats(heartbeatMonitor())]),
}))
vi.mock('../api/auth', () => ({ me: vi.fn() }))
vi.mock('../api/session', () => ({ updateSessionUser: vi.fn() }))
vi.mock('../auth/authContext', () => ({ useAuth: () => ({ user: auth.user.current }) }))

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/billing" element={<BillingPage />} />
        <Route path="/status-page" element={<StatusPageEditor />} />
        <Route path="/status/:slug" element={<PublicStatusPage />} />
      </Routes>
    </MemoryRouter>,
  )
  return userEvent.setup()
}

beforeEach(() => {
  auth.user.current = user({ plan: 'FREE' })
  billingApi.getBillingSummary.mockResolvedValue(billing())
  billingApi.startCheckout.mockResolvedValue({ url: 'https://checkout.stripe.test/session' })
  billingApi.openPortal.mockResolvedValue({ url: 'https://billing.stripe.test/portal' })
  statusApi.getMyStatusPage.mockResolvedValue(null)
  statusApi.saveStatusPage.mockImplementation(async (page: unknown) => page)
})

describe('Billing', () => {
  it('upgrades from Free through Stripe Checkout', async () => {
    const user = renderAt('/billing')

    await user.click(await screen.findByRole('button', { name: 'Upgrade to Pro' }))
    const dialog = screen.getByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Continue to payment' }))

    expect(billingApi.startCheckout).toHaveBeenCalledWith('PRO')
    expect(billingApi.openPortal).not.toHaveBeenCalled()
  })

  it('changes a paid plan in the Stripe portal instead', async () => {
    auth.user.current = user({ plan: 'PRO' })
    billingApi.getBillingSummary.mockResolvedValue(billing({ plan: 'PRO', status: 'active', currentPeriodEnd: '2026-02-01T00:00:00Z' }))
    const ui = renderAt('/billing')

    await ui.click(await screen.findByRole('button', { name: 'Switch to Free' }))
    await ui.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Continue to Stripe' }))

    expect(billingApi.openPortal).toHaveBeenCalled()
    expect(billingApi.startCheckout).not.toHaveBeenCalled()
  })
})

describe('Status page editor', () => {
  it('creates a page from the active monitors', async () => {
    const user = renderAt('/status-page')

    await user.click(await screen.findByRole('button', { name: 'Create status page' }))
    await user.click(screen.getByRole('button', { name: 'Create page' }))

    expect(statusApi.saveStatusPage).toHaveBeenCalledTimes(1)
    const saved = statusApi.saveStatusPage.mock.calls[0][0]
    expect(saved.slug).toBe('alice')
    expect(saved.monitors.map((m: { monitorId: number }) => m.monitorId)).toEqual([1, 2])
  })

  it('shows a taken address next to the field', async () => {
    statusApi.saveStatusPage.mockRejectedValue(new ApiError(409, 'That address is taken', { slug: 'That address is already taken' }))
    const user = renderAt('/status-page')

    await user.click(await screen.findByRole('button', { name: 'Create status page' }))
    await user.click(screen.getByRole('button', { name: 'Create page' }))

    expect(await screen.findByText('That address is already taken')).toBeInTheDocument()
  })
})

describe('Public status page', () => {
  const page: PublicPage = {
    title: 'Acme Status',
    description: 'Live status of Acme.',
    overall: 'OPERATIONAL',
    historyDays: 7,
    components: [{ name: 'Public API', status: 'OPERATIONAL', uptimePct: 99.9, days: [] }],
    incidents: [],
    generatedAt: '2026-01-01T12:00:00Z',
  }

  it('shows the published components under their public names', async () => {
    statusApi.getPublicStatusPage.mockResolvedValue(page)
    renderAt('/status/acme')

    expect(await screen.findByRole('heading', { name: 'Acme Status' })).toBeInTheDocument()
    expect(screen.getByText('All systems operational')).toBeInTheDocument()
    expect(screen.getByText('Public API')).toBeInTheDocument()
    expect(statusApi.getPublicStatusPage).toHaveBeenCalledWith('acme')
  })

  it('treats an unpublished or unknown page as not found', async () => {
    statusApi.getPublicStatusPage.mockRejectedValue(new ApiError(404, 'Status page not found'))
    renderAt('/status/nope')

    expect(await screen.findByText('Status page not found')).toBeInTheDocument()
  })
})

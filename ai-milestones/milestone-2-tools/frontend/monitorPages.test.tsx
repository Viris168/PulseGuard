import { OPEN_ASK_AI } from '../lib/events'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { check, heartbeatMonitor, httpMonitor, stats, user, withStats } from '../test/fixtures'
import { MonitorDetailPage } from './MonitorDetailPage'
import { MonitorsPage } from './MonitorsPage'

const api = vi.hoisted(() => ({
  listMonitors: vi.fn(),
  pauseMonitor: vi.fn(),
  resumeMonitor: vi.fn(),
  getMonitor: vi.fn(),
  getMonitorStats: vi.fn(),
  listChecks: vi.fn(),
  listPings: vi.fn(),
  sendTestPing: vi.fn(),
}))
vi.mock('../api/monitors', () => api)
vi.mock('../api/incidents', () => ({ listIncidents: vi.fn().mockResolvedValue([]) }))
vi.mock('../auth/authContext', () => ({ useAuth: () => ({ user: user() }) }))

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/monitors" element={<MonitorsPage />} />
        <Route path="/monitors/:id" element={<MonitorDetailPage />} />
      </Routes>
    </MemoryRouter>,
  )
  return userEvent.setup()
}

beforeEach(() => {
  api.listMonitors.mockResolvedValue([withStats(httpMonitor()), withStats(heartbeatMonitor(), { lastResponseTimeMs: null })])
  api.pauseMonitor.mockImplementation(async (id: number) => ({ ...httpMonitor({ id }), isActive: false }))
  api.getMonitorStats.mockResolvedValue(stats())
  api.listChecks.mockResolvedValue([check()])
  api.listPings.mockResolvedValue([{ id: 1, receivedAt: '2026-01-01T11:00:00Z', sourceIp: '203.0.113.7' }])
})

describe('Monitors list', () => {
  it('shows HTTP and heartbeat monitors side by side', async () => {
    renderAt('/monitors')

    expect(await screen.findByRole('link', { name: /Payments API/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Nightly backup/ })).toBeInTheDocument()
    expect(screen.getByText(/Heartbeat · every 1d \(\+1h grace\)/)).toBeInTheDocument()
  })

  it('pauses a monitor from its row', async () => {
    const user = renderAt('/monitors')
    const row = (await screen.findByRole('link', { name: /Payments API/ })).closest('li') as HTMLElement

    await user.click(within(row).getByRole('button', { name: 'Pause' }))

    expect(api.pauseMonitor).toHaveBeenCalledWith(1)
  })

  it('invites the first monitor when there are none', async () => {
    api.listMonitors.mockResolvedValue([])
    renderAt('/monitors')

    expect(await screen.findByText('No monitors yet')).toBeInTheDocument()
  })
})

describe('Monitor details', () => {
  it('opens Ask AI about this monitor', async () => {
    api.getMonitor.mockResolvedValue(httpMonitor())
    const opened = vi.fn()
    const listener = (e: Event) => opened((e as CustomEvent).detail)
    window.addEventListener(OPEN_ASK_AI, listener)
    const user = renderAt('/monitors/1')

    await user.click(await screen.findByRole('button', { name: 'Ask AI' }))

    expect(opened).toHaveBeenCalledWith({ monitorId: 1, label: 'Payments API' })
    window.removeEventListener(OPEN_ASK_AI, listener)
  })

  it('shows what an HTTP monitor expects', async () => {
    api.getMonitor.mockResolvedValue(httpMonitor())
    renderAt('/monitors/1')

    expect(await screen.findByRole('heading', { name: 'Payments API' })).toBeInTheDocument()
    expect(screen.getByText(/Expects\s+200 or 204/)).toBeInTheDocument()
  })

  it('gives a heartbeat its ping URL, recent pings and a test ping', async () => {
    const monitor = heartbeatMonitor()
    api.getMonitor.mockResolvedValue(monitor)
    api.sendTestPing.mockResolvedValue({ ...monitor, lastCheckedAt: '2026-01-01T12:00:00Z' })
    const user = renderAt('/monitors/2')

    expect(await screen.findByRole('heading', { name: 'Ping URL' })).toBeInTheDocument()
    expect(screen.getAllByText(/api\/ping\/AbCdEfGhIjKlMnOpQrStUvWx/).length).toBeGreaterThan(0)
    expect(await screen.findByText('203.0.113.7')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /Send test ping/ }))

    expect(api.sendTestPing).toHaveBeenCalledWith(2)
  })
})

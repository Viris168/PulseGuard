import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/errors'
import type { Monitor } from '../types/monitor'
import { MonitorFormPage } from './MonitorFormPage'

const api = vi.hoisted(() => ({
  createMonitor: vi.fn(),
  updateMonitor: vi.fn(),
  getMonitor: vi.fn(),
}))
vi.mock('../api/monitors', () => api)
vi.mock('../api/billing', () => ({
  getBillingSummary: vi.fn().mockResolvedValue({ plan: 'PRO', usage: { monitors: 0 } }),
}))
vi.mock('../auth/authContext', () => ({
  useAuth: () => ({ user: { id: 1, name: 'Alice', email: 'a@example.com', plan: 'PRO', emailVerified: true } }),
}))

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/monitors/new" element={<MonitorFormPage />} />
        <Route path="/monitors/:id/edit" element={<MonitorFormPage />} />
        <Route path="/monitors" element={<p>Monitors list</p>} />
        <Route path="/monitors/:id" element={<p>Monitor page</p>} />
      </Routes>
    </MemoryRouter>,
  )
  return userEvent.setup()
}

const saved: Monitor = {
  id: 7,
  type: 'HTTP',
  name: 'GraphQL',
  url: 'https://api.example.com/graphql',
  method: 'POST',
  expectedStatus: 200,
  expectedStatuses: [200, 204],
  intervalSeconds: 300,
  timeoutMs: 5000,
  state: 'UP',
  isActive: true,
  lastCheckedAt: null,
  createdAt: '2026-01-01T00:00:00Z',
  graceSeconds: null,
  pingUrl: null,
  headers: [
    { name: 'Authorization', value: null, secret: true },
    { name: 'X-Env', value: 'prod', secret: false },
  ],
  requestBody: '{"query":"{ health }"}',
}

beforeEach(() => {
  api.createMonitor.mockResolvedValue({ ...saved, id: 8 })
  api.updateMonitor.mockResolvedValue(saved)
  api.getMonitor.mockResolvedValue(saved)
})

describe('MonitorFormPage', () => {
  it('sends several status codes, headers and a POST body', async () => {
    const user = renderAt('/monitors/new')
    await user.type(await screen.findByLabelText('Name'), 'GraphQL')
    const url = screen.getByLabelText('URL')
    await user.clear(url)
    await user.type(url, 'https://api.example.com/graphql')
    await user.selectOptions(screen.getByLabelText('Method'), 'POST')
    const status = screen.getByLabelText('Expected status')
    await user.clear(status)
    await user.type(status, '200, 204')
    await user.click(screen.getByRole('button', { name: /add header/i }))
    await user.type(screen.getByLabelText('Header 1 name'), 'X-Env')
    await user.type(screen.getByLabelText('Header 1 value'), 'prod')
    await user.click(screen.getByLabelText('Body'))
    await user.paste('{"query":"{ health }"}')

    await user.click(screen.getByRole('button', { name: 'Create monitor' }))

    await waitFor(() => expect(api.createMonitor).toHaveBeenCalledTimes(1))
    expect(api.createMonitor.mock.calls[0][0]).toMatchObject({
      type: 'HTTP',
      method: 'POST',
      expectedStatuses: [200, 204],
      headers: [{ name: 'X-Env', value: 'prod' }],
      requestBody: '{"query":"{ health }"}',
    })
  })

  it('only offers a body for POST and PUT', async () => {
    const user = renderAt('/monitors/new')
    await screen.findByLabelText('Name')
    expect(screen.queryByLabelText('Body')).not.toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('Method'), 'PUT')

    expect(screen.getByLabelText('Body')).toBeInTheDocument()
  })

  it('catches a bad status list and a forbidden header before calling the server', async () => {
    const user = renderAt('/monitors/new')
    await user.type(await screen.findByLabelText('Name'), 'API')
    const url = screen.getByLabelText('URL')
    await user.clear(url)
    await user.type(url, 'https://api.example.com')
    const status = screen.getByLabelText('Expected status')
    await user.clear(status)
    await user.type(status, '200, 99')
    await user.click(screen.getByRole('button', { name: /add header/i }))
    await user.type(screen.getByLabelText('Header 1 name'), 'Host')
    await user.type(screen.getByLabelText('Header 1 value'), '169.254.169.254')

    await user.click(screen.getByRole('button', { name: 'Create monitor' }))

    expect(await screen.findByText('Enter status codes from 100 to 599, e.g. 200, 204')).toBeInTheDocument()
    expect(screen.getByText("The Host header is set by PulseGuard and can't be changed")).toBeInTheDocument()
    expect(api.createMonitor).not.toHaveBeenCalled()
  })

  it('never shows a saved secret and sends it back as "keep" (null) when left blank', async () => {
    const user = renderAt('/monitors/7/edit')
    const secret = await screen.findByLabelText('Header 1 value')
    expect(secret).toHaveValue('')
    expect(secret).toHaveAttribute('placeholder', 'Unchanged')
    expect(screen.getByLabelText('Header 2 value')).toHaveValue('prod')
    expect(screen.getByLabelText('Expected status')).toHaveValue('200, 204')

    await user.click(screen.getByRole('button', { name: 'Save changes' }))

    await waitFor(() => expect(api.updateMonitor).toHaveBeenCalledTimes(1))
    expect(api.updateMonitor.mock.calls[0][1].headers).toEqual([
      { name: 'Authorization', value: null },
      { name: 'X-Env', value: 'prod' },
    ])
  })

  it('shows a field error from the server next to its field', async () => {
    api.createMonitor.mockRejectedValueOnce(
      new ApiError(400, 'Validation failed', { headers: 'Header values can\'t contain line breaks' }),
    )
    const user = renderAt('/monitors/new')
    await user.type(await screen.findByLabelText('Name'), 'API')
    const url = screen.getByLabelText('URL')
    await user.clear(url)
    await user.type(url, 'https://api.example.com')

    await user.click(screen.getByRole('button', { name: 'Create monitor' }))

    expect(await screen.findByText("Header values can't contain line breaks")).toBeInTheDocument()
    expect(screen.getByText('Fix the highlighted fields.')).toBeInTheDocument()
  })
})

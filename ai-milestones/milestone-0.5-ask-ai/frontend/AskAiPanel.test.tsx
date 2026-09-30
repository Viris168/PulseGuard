import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/errors'
import { httpMonitor, withStats } from '../../test/fixtures'
import { AskAiPanel } from './AskAiPanel'

const ai = vi.hoisted(() => ({ getAiAccess: vi.fn(), saveAiAccess: vi.fn(), getAiQuota: vi.fn(), askAi: vi.fn() }))
vi.mock('../../api/ai', () => ai)
const monitors = vi.hoisted(() => ({ listMonitors: vi.fn() }))
vi.mock('../../api/monitors', () => monitors)

// jsdom has no layout, so no scrolling either.
Element.prototype.scrollTo ??= () => {}

const ON = { enabled: true, allMonitors: true, monitorIds: [] }

function renderPanel() {
  render(
    <MemoryRouter>
      <AskAiPanel open onClose={() => {}} />
    </MemoryRouter>,
  )
  return userEvent.setup()
}

beforeEach(() => {
  ai.getAiAccess.mockResolvedValue(ON)
  ai.getAiQuota.mockResolvedValue({ used: 0, limit: 5 })
  monitors.listMonitors.mockResolvedValue([withStats(httpMonitor({ id: 7, name: 'Shop API' }))])
})

describe('Ask AI panel', () => {
  it('asks for consent before anything is shared', async () => {
    ai.getAiAccess.mockResolvedValue({ enabled: false, allMonitors: true, monitorIds: [] })
    ai.saveAiAccess.mockImplementation(async (a) => a)
    const user = renderPanel()

    await user.click(await screen.findByRole('button', { name: 'Review permissions' }))
    await user.click(screen.getByRole('button', { name: 'Enable Ask AI' }))

    expect(ai.saveAiAccess).toHaveBeenCalledWith({ enabled: true, allMonitors: true, monitorIds: [7] })
    expect(await screen.findByText('What do you want to know?')).toBeInTheDocument()
  })

  it("shows the server's answer with links to the pages it names", async () => {
    ai.askAi.mockResolvedValue({
      answer: '**Shop API** is down: it returns 503 errors.',
      links: [{ label: 'Open Shop API', to: '/monitors/7' }],
      quota: { used: 1, limit: 5 },
    })
    const user = renderPanel()

    await user.type(await screen.findByLabelText('Your question'), 'Is anything down?{Enter}')

    expect(ai.askAi).toHaveBeenCalledWith('Is anything down?')
    expect(await screen.findByText('Shop API', { selector: 'strong' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open Shop API' })).toHaveAttribute('href', '/monitors/7')
    expect(screen.getByText(/4 of 5 questions left today/)).toBeInTheDocument()
  })

  it('offers an upgrade at the daily limit', async () => {
    ai.askAi.mockRejectedValue(new ApiError(429, "You've used all 5 questions for today on the Free plan."))
    const user = renderPanel()

    await user.type(await screen.findByLabelText('Your question'), 'One more?{Enter}')

    expect(await screen.findByText(/used all 5 questions/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Upgrade for more questions' })).toBeInTheDocument()
  })

  it("says so when the model couldn't answer", async () => {
    ai.askAi.mockRejectedValue(new ApiError(503, "Ask AI couldn't answer just now. Try again in a moment; this question wasn't counted."))
    const user = renderPanel()

    await user.type(await screen.findByLabelText('Your question'), 'Is anything down?{Enter}')

    expect(await screen.findByText(/wasn't counted/)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Upgrade for more questions' })).not.toBeInTheDocument()
  })

  it('shows an error instead of spinning forever when it cannot load', async () => {
    ai.getAiAccess.mockRejectedValue(new ApiError(0, 'Cannot reach the PulseGuard server. Is the backend running?'))
    renderPanel()

    expect(await screen.findByText(/Cannot reach the PulseGuard server/)).toBeInTheDocument()
  })
})

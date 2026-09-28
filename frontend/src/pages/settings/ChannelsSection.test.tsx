import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { NotificationChannel } from '../../types/incident'
import { ChannelsSection } from './ChannelsSection'

const api = vi.hoisted(() => ({
  listChannels: vi.fn(),
  createChannel: vi.fn(),
  resendChannelConfirmation: vi.fn(),
  sendTestAlert: vi.fn(),
  setChannelEnabled: vi.fn(),
  deleteChannel: vi.fn(),
}))
vi.mock('../../api/channels', async (importActual) => ({
  ...(await importActual<typeof import('../../api/channels')>()),
  ...api,
}))
vi.mock('../../auth/authContext', () => ({
  useAuth: () => ({ user: { id: 1, name: 'Alice', email: 'alice@example.com', plan: 'PRO', emailVerified: true } }),
}))

const own: NotificationChannel = { id: 1, type: 'EMAIL', target: 'alice@example.com', enabled: true, awaitingConfirmation: false }
const teammate: NotificationChannel = { id: 2, type: 'EMAIL', target: 'team@example.com', enabled: true, awaitingConfirmation: true }

function renderSection() {
  render(
    <MemoryRouter>
      <ChannelsSection />
    </MemoryRouter>,
  )
  return userEvent.setup()
}

beforeEach(() => {
  api.listChannels.mockResolvedValue([own, teammate])
  api.resendChannelConfirmation.mockResolvedValue(undefined)
  api.sendTestAlert.mockResolvedValue(undefined)
})

const row = async (text: string) => (await screen.findByText(text)).closest('li') as HTMLElement

describe('ChannelsSection', () => {
  it('marks an address that has not confirmed, and offers a new link instead of a test', async () => {
    const user = renderSection()
    const pending = await row('team@example.com')

    expect(within(pending).getByText('Awaiting confirmation')).toBeInTheDocument()
    expect(within(pending).queryByRole('button', { name: 'Send test' })).not.toBeInTheDocument()

    await user.click(within(pending).getByRole('button', { name: 'Resend link' }))

    expect(api.resendChannelConfirmation).toHaveBeenCalledWith(2)
    expect(await within(pending).findByText('Confirmation link sent again.')).toBeInTheDocument()
  })

  it('lets a confirmed channel send a test alert', async () => {
    const user = renderSection()
    const mine = await row('alice@example.com')

    expect(within(mine).queryByText('Awaiting confirmation')).not.toBeInTheDocument()
    await user.click(within(mine).getByRole('button', { name: 'Send test' }))

    expect(api.sendTestAlert).toHaveBeenCalledWith(1)
  })

  it('tells the owner a new address was sent a confirmation link', async () => {
    api.createChannel.mockResolvedValue({ id: 3, type: 'EMAIL', target: 'ops@example.com', enabled: true, awaitingConfirmation: true })
    const user = renderSection()
    await screen.findByText('team@example.com')

    await user.type(screen.getByLabelText('Email address'), 'ops@example.com')
    await user.click(screen.getByRole('button', { name: 'Add channel' }))

    expect(await screen.findByText('Added. We emailed ops@example.com a link to confirm it wants these alerts.')).toBeInTheDocument()
    expect(api.createChannel).toHaveBeenCalledWith({ type: 'EMAIL', target: 'ops@example.com' })
  })

  it('no longer offers SMS', async () => {
    renderSection()
    await screen.findByText('team@example.com')

    expect(screen.getByRole('radio', { name: /Email/ })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /Slack/ })).toBeInTheDocument()
    expect(screen.queryByRole('radio', { name: /SMS/ })).not.toBeInTheDocument()
  })
})

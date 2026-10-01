import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ComponentProps } from 'react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { HelpSource, StreamResult } from '../../api/ai'
import { ApiError } from '../../api/errors'
import { httpMonitor, withStats } from '../../test/fixtures'
import { AskAiPanel } from './AskAiPanel'

const ai = vi.hoisted(() => ({
  getAiAccess: vi.fn(),
  saveAiAccess: vi.fn(),
  getAiQuota: vi.fn(),
  listConversations: vi.fn(),
  createConversation: vi.fn(),
  getMessages: vi.fn(),
  renameConversation: vi.fn(),
  deleteConversation: vi.fn(),
  rateMessage: vi.fn(),
  streamMessage: vi.fn(),
}))
vi.mock('../../api/ai', () => ai)
const monitors = vi.hoisted(() => ({ listMonitors: vi.fn() }))
vi.mock('../../api/monitors', () => monitors)

// jsdom has no layout, so no scrolling either.
Element.prototype.scrollTo ??= () => {}

const ON = { enabled: true, allMonitors: true, monitorIds: [] }
const CHAT = {
  id: 42,
  title: 'Is Health down?',
  createdAt: '2026-09-30T09:00:00Z',
  updatedAt: '2026-09-30T09:05:00Z',
  contextMonitorId: null,
  contextIncidentId: null,
}
const DONE: StreamResult = {
  kind: 'done',
  done: { questionId: 1, answerId: 2, status: 'COMPLETE', quota: { used: 1, limit: 5 } },
}

function Where() {
  const { pathname, hash } = useLocation()
  return <p data-testid="where">{pathname + hash}</p>
}

function renderPanel(page: ComponentProps<typeof AskAiPanel>['page'] = null) {
  render(
    <MemoryRouter initialEntries={['/monitors']}>
      <AskAiPanel open onClose={() => {}} page={page} />
      <Routes>
        <Route path="*" element={<Where />} />
      </Routes>
    </MemoryRouter>,
  )
  return userEvent.setup()
}

/** A lookup as the stream reports it: a label, and the docs sections a help search found. */
type Lookup = string | { label: string; sources: HelpSource[] }

/** Plays back an answer: each lookup through onTool, each piece through onDelta, then the result. */
function answers(pieces: string[], result: StreamResult = DONE, lookups: Lookup[] = []) {
  ai.streamMessage.mockImplementation(
    async (
      _id: number,
      _q: string,
      { onDelta, onTool }: { onDelta: (t: string) => void; onTool?: (l: string, s: HelpSource[]) => void },
    ) => {
      lookups.forEach((l) => (typeof l === 'string' ? onTool?.(l, []) : onTool?.(l.label, l.sources)))
      pieces.forEach(onDelta)
      return result
    },
  )
}

const SLACK_SETUP = { title: 'Slack alerts › Setting it up', url: '/docs/slack-alerts#setting-it-up' }
const SLACK_PLANS = { title: 'Plans and limits › What each plan includes', url: '/docs/plans-and-limits#what-each-plan-includes' }
const SLACK_TEST = { title: 'Slack alerts › Testing the channel', url: '/docs/slack-alerts#testing-the-channel' }

beforeEach(() => {
  ai.getAiAccess.mockResolvedValue(ON)
  ai.getAiQuota.mockResolvedValue({ used: 0, limit: 5 })
  ai.listConversations.mockResolvedValue([])
  ai.createConversation.mockResolvedValue(CHAT)
  ai.rateMessage.mockResolvedValue(undefined)
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

  it('starts a chat on the first question and shows the answer as it streams in', async () => {
    answers(['**Shop API** is ', 'down: it returns 503.'])
    const user = renderPanel()

    await user.type(await screen.findByLabelText('Your question'), 'Is anything down?{Enter}')

    expect(ai.createConversation).toHaveBeenCalledTimes(1)
    expect(ai.streamMessage).toHaveBeenCalledWith(42, 'Is anything down?', expect.anything())
    expect(await screen.findByText('Shop API', { selector: 'strong' })).toBeInTheDocument()
    expect(screen.getByText(/down: it returns 503/)).toBeInTheDocument()
    expect(screen.getByText(/4 of 5 questions left today/)).toBeInTheDocument()
  })

  it('shows a list that follows a line of text in the same paragraph', async () => {
    answers(['In the last 7 days, there were two incidents:\n- Tue 29 Sep, 15:03 to 15:42\n- Tue 29 Sep, 14:36 to 15:06\n\nUptime is **60%**.'])
    const user = renderPanel()

    await user.type(await screen.findByLabelText('Your question'), 'Any incidents?{Enter}')

    expect(await screen.findByText('In the last 7 days, there were two incidents:')).toBeInTheDocument()
    const items = screen.getAllByRole('listitem')
    expect(items.map((li) => li.textContent)).toEqual(['Tue 29 Sep, 15:03 to 15:42', 'Tue 29 Sep, 14:36 to 15:06'])
    expect(screen.getByText('60%', { selector: 'strong' })).toBeInTheDocument()
  })

  it('shows what it looked up above the answer', async () => {
    answers(['Health was up 99.82%.'], DONE, ['Checked uptime for Health, 2026-09-01 to 2026-09-03'])
    const user = renderPanel()

    await user.type(await screen.findByLabelText('Your question'), 'Uptime of Health, 1 to 3 Sep?{Enter}')

    expect(await screen.findByText('Health was up 99.82%.')).toBeInTheDocument()
    const lookups = screen.getByRole('list', { name: 'What Ask AI looked up' })
    expect(within(lookups).getByText('Checked uptime for Health, 2026-09-01 to 2026-09-03')).toBeInTheDocument()
  })

  it('lists the docs sections under a docs answer, numbered, linking to each section', async () => {
    answers(['Create an incoming webhook [1], on Pro or Business [2].'], DONE, [
      { label: 'Searched the help docs for "slack"', sources: [SLACK_SETUP, SLACK_PLANS] },
      // A second search finding a section again doesn't number it twice.
      { label: 'Searched the help docs for "slack test"', sources: [SLACK_SETUP, SLACK_TEST] },
    ])
    const user = renderPanel()

    await user.type(await screen.findByLabelText('Your question'), 'How do I set up Slack?{Enter}')

    const sources = await screen.findByRole('list', { name: 'Sources' })
    const items = within(sources).getAllByRole('listitem')
    expect(items.map((li) => li.textContent)).toEqual([
      '[1]Slack alerts › Setting it up',
      '[2]Plans and limits › What each plan includes',
      '[3]Slack alerts › Testing the channel',
    ])
    await user.click(within(sources).getByRole('link', { name: 'Slack alerts › Setting it up' }))

    expect(screen.getByTestId('where')).toHaveTextContent('/docs/slack-alerts#setting-it-up')
    expect(screen.getByLabelText('Your question')).toBeInTheDocument() // the panel stays open
  })

  it('links the citations in the text; a number with no source stays text', async () => {
    answers(['Add the webhook [1, 3]. Free has no Slack [2][3]. See also [9] and [1](https://evil.example).'], DONE, [
      { label: 'Searched the help docs for "slack"', sources: [SLACK_SETUP, SLACK_PLANS, SLACK_TEST] },
    ])
    const user = renderPanel()

    await user.type(await screen.findByLabelText('Your question'), 'Slack?{Enter}')

    await screen.findByRole('list', { name: 'Sources' })
    const cited = screen.getAllByRole('link', { name: /^Source \d/ })
    expect(cited.map((a) => [a.textContent, a.getAttribute('href')])).toEqual([
      ['1', '/docs/slack-alerts#setting-it-up'],
      ['3', '/docs/slack-alerts#testing-the-channel'],
      ['2', '/docs/plans-and-limits#what-each-plan-includes'],
      ['3', '/docs/slack-alerts#testing-the-channel'],
    ])
    // [9] has no source; "[1](…)" is a link the model wrote, shown as its text.
    expect(screen.getByText(/See also \[9\] and/)).toHaveTextContent('See also [9] and 1.')
    expect(document.querySelector('a[href*="evil"]')).toBeNull()
  })

  it('renders lists, code, tables and code blocks in an answer', async () => {
    answers([
      'Two steps:\n\n1. Create a webhook\n2. Paste it into `Settings`\n\n| Plan | Slack |\n| --- | --- |\n| Free | No |\n\n```\ncurl -fsS https://example.com/api/ping/abc\n```\n\nFirst line\nsecond line',
    ])
    const user = renderPanel()

    await user.type(await screen.findByLabelText('Your question'), 'How?{Enter}')

    const steps = await screen.findAllByRole('listitem')
    expect(steps[0].closest('ol')).not.toBeNull()
    expect(steps.map((li) => li.textContent)).toEqual(['Create a webhook', 'Paste it into Settings'])
    expect(screen.getByText('Settings', { selector: 'code' })).toBeInTheDocument()
    expect(within(screen.getByRole('table')).getByRole('cell', { name: 'Free' })).toBeInTheDocument()
    expect(document.querySelector('pre > code')).toHaveTextContent('curl -fsS https://example.com/api/ping/abc')
    expect(screen.getByText(/First line/).textContent).toBe('First line\nsecond line')
  })

  it('never follows a link or loads an image the model wrote', async () => {
    answers(['Read [the guide](https://evil.example/guide) or [this](/billing). ![tracker](https://evil.example/pixel.png)'])
    const user = renderPanel()

    await user.type(await screen.findByLabelText('Your question'), 'Guide?{Enter}')

    expect(await screen.findByText('the guide')).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'the guide' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'this' })).not.toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.getByText('tracker')).toBeInTheDocument()
  })

  it('links citations inside a list and bold text, but not inside code', async () => {
    answers(['- **Set it up [1]**\n- Then `[1]` in code'], DONE, [{ label: 'Searched the help docs for "slack"', sources: [SLACK_SETUP] }])
    const user = renderPanel()

    await user.type(await screen.findByLabelText('Your question'), 'Slack?{Enter}')

    await screen.findByRole('list', { name: 'Sources' })
    const cited = screen.getAllByRole('link', { name: /^Source 1/ })
    expect(cited).toHaveLength(1)
    expect(cited[0].closest('strong')).not.toBeNull()
    expect(screen.getByText('[1]', { selector: 'code' })).toBeInTheDocument()
  })

  it('leaves citations as text in an answer that has no sources', async () => {
    answers(['Health had 3 incidents [1].'])
    const user = renderPanel()

    await user.type(await screen.findByLabelText('Your question'), 'Incidents?{Enter}')

    expect(await screen.findByText('Health had 3 incidents [1].')).toBeInTheDocument()
    expect(screen.queryByRole('list', { name: 'Sources' })).not.toBeInTheDocument()
  })

  it('starts a chat about an incident from its page', async () => {
    answers(['It returned 503s.'])
    const user = renderPanel({ incidentId: 5, label: 'the incident on Health (Tue 29 Sep, 14:36)', key: 1 })

    expect(await screen.findByText('About the incident on Health (Tue 29 Sep, 14:36)')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Why did this happen?' }))

    expect(ai.createConversation).toHaveBeenCalledWith({ incidentId: 5 })
    expect(await screen.findByText('It returned 503s.')).toBeInTheDocument()
  })

  it('starts a chat about a monitor from its page', async () => {
    answers(['Fine all week.'])
    const user = renderPanel({ monitorId: 7, label: 'Shop API', key: 1 })

    await user.click(await screen.findByRole('button', { name: 'How has Shop API been this week?' }))

    expect(ai.createConversation).toHaveBeenCalledWith({ monitorId: 7 })
  })

  it('a new chat is no longer about the page', async () => {
    answers(['Hi.'])
    const user = renderPanel({ monitorId: 7, label: 'Shop API', key: 1 })

    await screen.findByText('About Shop API')
    await user.click(screen.getByRole('button', { name: 'New chat' }))
    await user.type(screen.getByLabelText('Your question'), 'Anything down?{Enter}')

    expect(screen.queryByText('About Shop API')).not.toBeInTheDocument()
    expect(ai.createConversation).toHaveBeenCalledWith(undefined)
  })

  it('sends a follow-up in the same chat', async () => {
    answers(['Yes.'])
    const user = renderPanel()
    const box = await screen.findByLabelText('Your question')

    await user.type(box, 'Is Health down?{Enter}')
    await screen.findByText('Yes.')
    await user.type(box, 'Has that happened before?{Enter}')

    await waitFor(() => expect(ai.streamMessage).toHaveBeenCalledTimes(2))
    expect(ai.createConversation).toHaveBeenCalledTimes(1)
    expect(ai.streamMessage).toHaveBeenLastCalledWith(42, 'Has that happened before?', expect.anything())
  })

  it('turns Send into Stop while writing, and keeps what was written', async () => {
    let finish: (r: StreamResult) => void = () => {}
    ai.streamMessage.mockImplementation(
      (_id: number, _q: string, { onDelta, signal }: { onDelta: (t: string) => void; signal: AbortSignal }) =>
        new Promise<StreamResult>((resolve) => {
          finish = resolve
          onDelta('It means the server')
          signal.addEventListener('abort', () => resolve({ kind: 'stopped' }))
        }),
    )
    const user = renderPanel()

    await user.type(await screen.findByLabelText('Your question'), 'Explain 503{Enter}')
    expect(await screen.findByText('It means the server')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Stop' }))

    expect(await screen.findByText('Stopped')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Send' })).toBeInTheDocument()
    act(() => finish(DONE)) // a late result after Stop changes nothing
  })

  it('offers an upgrade at the daily limit', async () => {
    ai.streamMessage.mockRejectedValue(new ApiError(429, "You've used all 5 questions for today on the Free plan."))
    const user = renderPanel()

    await user.type(await screen.findByLabelText('Your question'), 'One more?{Enter}')

    expect(await screen.findByText(/used all 5 questions/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Upgrade for more questions' })).toBeInTheDocument()
  })

  it("offers Try again when the model couldn't answer, and it wasn't counted", async () => {
    answers([], { kind: 'error', error: { message: "Ask AI couldn't answer just now.", counted: false, answerId: null } })
    const user = renderPanel()

    await user.type(await screen.findByLabelText('Your question'), 'Is anything down?{Enter}')
    expect(await screen.findByText(/couldn't answer just now/)).toBeInTheDocument()

    answers(['All up.'])
    await user.click(screen.getByRole('button', { name: 'Try again' }))

    expect(await screen.findByText('All up.')).toBeInTheDocument()
    expect(ai.streamMessage).toHaveBeenLastCalledWith(42, 'Is anything down?', expect.anything())
  })

  it('rates an answer with thumbs up or down', async () => {
    answers(['Yes.'])
    const user = renderPanel()

    await user.type(await screen.findByLabelText('Your question'), 'Is Health down?{Enter}')
    await user.click(await screen.findByRole('button', { name: 'Bad answer' }))

    expect(ai.rateMessage).toHaveBeenCalledWith(2, -1)
    expect(screen.getByRole('button', { name: 'Bad answer' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('reopens a saved chat from the list', async () => {
    ai.listConversations.mockResolvedValue([{ ...CHAT, contextMonitorId: 7 }])
    ai.getMessages.mockResolvedValue([
      { id: 1, role: 'USER', content: 'Is Health down?', status: 'COMPLETE', createdAt: CHAT.createdAt, rating: null, lookups: [] },
      {
        id: 2,
        role: 'ASSISTANT',
        content: 'Yes, it returns 503.',
        status: 'COMPLETE',
        createdAt: CHAT.createdAt,
        rating: 1,
        lookups: ['Checked recent failed checks for Shop API'],
        sources: [],
      },
    ])
    const user = renderPanel()

    await user.click(await screen.findByRole('button', { name: 'Your chats' }))
    expect(screen.getByText(/About Shop API ·/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /^Is Health down\?/ }))

    expect(await screen.findByText('Yes, it returns 503.')).toBeInTheDocument()
    expect(ai.getMessages).toHaveBeenCalledWith(42)
    expect(screen.getByRole('button', { name: 'Good answer' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByText('Checked recent failed checks for Shop API')).toBeInTheDocument()
    expect(screen.getByText('About Shop API')).toBeInTheDocument()
  })

  it('shows the saved sources when a docs answer is reopened', async () => {
    ai.listConversations.mockResolvedValue([CHAT])
    ai.getMessages.mockResolvedValue([
      { id: 1, role: 'USER', content: 'Slack?', status: 'COMPLETE', createdAt: CHAT.createdAt, rating: null, lookups: [], sources: [] },
      {
        id: 2,
        role: 'ASSISTANT',
        content: 'Create an incoming webhook [1].',
        status: 'COMPLETE',
        createdAt: CHAT.createdAt,
        rating: null,
        lookups: ['Searched the help docs for "slack"'],
        sources: [SLACK_SETUP],
      },
    ])
    const user = renderPanel()

    await user.click(await screen.findByRole('button', { name: 'Your chats' }))
    await user.click(screen.getByRole('button', { name: /^Is Health down\?/ }))

    const sources = await screen.findByRole('list', { name: 'Sources' })
    expect(within(sources).getByRole('link', { name: 'Slack alerts › Setting it up' })).toHaveAttribute('href', '/docs/slack-alerts#setting-it-up')
    expect(screen.getByRole('link', { name: 'Source 1: Slack alerts › Setting it up' })).toHaveAttribute('href', '/docs/slack-alerts#setting-it-up')
  })

  it('renames and deletes chats', async () => {
    ai.listConversations.mockResolvedValue([CHAT])
    ai.renameConversation.mockResolvedValue({ ...CHAT, title: 'Health outage' })
    ai.deleteConversation.mockResolvedValue(undefined)
    const user = renderPanel()

    await user.click(await screen.findByRole('button', { name: 'Your chats' }))
    await user.click(screen.getByRole('button', { name: 'Rename Is Health down?' }))
    const name = screen.getByLabelText('Chat name')
    await user.clear(name)
    await user.type(name, 'Health outage{Enter}')

    expect(ai.renameConversation).toHaveBeenCalledWith(42, 'Health outage')
    const row = (await screen.findByText('Health outage')).closest('li')!
    await user.click(within(row).getByRole('button', { name: 'Delete Health outage' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByRole('heading', { name: 'Delete chat?' })).toBeInTheDocument()
    expect(within(dialog).getByText('Health outage')).toBeInTheDocument()
    expect(ai.deleteConversation).not.toHaveBeenCalled()
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }))

    expect(ai.deleteConversation).toHaveBeenCalledWith(42)
    expect(await screen.findByText(/No chats yet/)).toBeInTheDocument()
  })

  it('shows an error instead of spinning forever when it cannot load', async () => {
    ai.getAiAccess.mockRejectedValue(new ApiError(0, 'Cannot reach the PulseGuard server. Is the backend running?'))
    renderPanel()

    expect(await screen.findByText(/Cannot reach the PulseGuard server/)).toBeInTheDocument()
  })
})

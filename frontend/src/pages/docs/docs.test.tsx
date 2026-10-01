import evalYaml from '../../../../src/test/resources/help-eval.yaml?raw'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/errors'
import type { HelpArticle, HelpArticleSummary } from '../../api/help'
import { SignedInOrPublic } from '../../auth/RouteGuards'
import { SupportMenu } from '../../components/layout/HeaderActions'
import { SHOW_ASK_AI } from '../../lib/events'
import { helpAnchor } from '../../lib/helpAnchor'
import { DocArticlePage } from './DocArticlePage'
import { DocsPage } from './DocsPage'

const helpApi = vi.hoisted(() => ({ listHelpArticles: vi.fn(), getHelpArticle: vi.fn() }))
const auth = vi.hoisted(() => ({ status: 'anonymous' as 'loading' | 'anonymous' | 'authenticated' }))
vi.mock('../../api/help', () => helpApi)
vi.mock('../../auth/authContext', () => ({ useAuth: () => ({ status: auth.status }) }))

// The real docs and the search eval, read by Vite: the backend's resources are outside frontend/.
const HELP_FILES = import.meta.glob<string>('../../../../src/main/resources/help/*.md', { query: '?raw', import: 'default', eager: true })
const HELP = Object.fromEntries(Object.entries(HELP_FILES).map(([path, text]) => [/([^/]+)\.md$/.exec(path)![1], text]))
const FRONT_MATTER = /^---\n([\s\S]*?)\n---\n/

/** An article as the backend serves it: HelpController strips the front matter. */
function realArticle(slug: string): HelpArticle {
  const text = HELP[slug].replace(/\r\n/g, '\n')
  const front = FRONT_MATTER.exec(text)![1]
  const field = (name: string) => new RegExp(`^${name}: (.+)$`, 'm').exec(front)![1].trim()
  return { slug, title: field('title'), summary: field('summary'), markdown: text.replace(FRONT_MATTER, '').trim() }
}

const realSlugs = Object.keys(HELP)

function Where() {
  const { pathname, hash } = useLocation()
  return <p data-testid="where">{pathname + hash}</p>
}

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route
          element={
            <SignedInOrPublic
              app={<div data-testid="app-layout"><SupportMenu /><Outlet /></div>}
              visitor={<div data-testid="public-layout"><Outlet /></div>}
            />
          }
        >
          <Route path="/docs" element={<DocsPage />} />
          <Route path="/docs/:slug" element={<DocArticlePage />} />
        </Route>
        <Route path="*" element={<Where />} />
      </Routes>
      <Where />
    </MemoryRouter>,
  )
  return userEvent.setup()
}

beforeEach(() => {
  auth.status = 'anonymous'
  helpApi.listHelpArticles.mockResolvedValue(realSlugs.map((s) => {
    const { slug, title, summary } = realArticle(s)
    return { slug, title, summary } satisfies HelpArticleSummary
  }))
  helpApi.getHelpArticle.mockImplementation(async (slug: string) => {
    if (!realSlugs.includes(slug)) throw new ApiError(404, `Help article not found: ${slug}`)
    return realArticle(slug)
  })
  Element.prototype.scrollIntoView = vi.fn()
})

describe('Docs list', () => {
  it('shows every article, grouped, and works signed out', async () => {
    renderAt('/docs')

    const groups = await screen.findAllByRole('region')
    expect(groups.map((g) => within(g).getByRole('heading').textContent)).toEqual([
      'Getting started',
      'Monitors and status pages',
      'Alerts',
      'Account and billing',
      'Ask AI',
    ])
    expect(within(groups[2]).getAllByRole('link').map((l) => l.getAttribute('href'))).toEqual([
      '/docs/email-alerts',
      '/docs/slack-alerts',
      '/docs/alert-delivery',
    ])
    expect(screen.getAllByRole('link', { name: /./ }).filter((l) => l.getAttribute('href')?.startsWith('/docs/'))).toHaveLength(realSlugs.length)
    expect(screen.getByTestId('public-layout')).toBeInTheDocument()
  })

  it('puts an article it has no group for under "More", so a new one is never hidden', async () => {
    helpApi.listHelpArticles.mockResolvedValue([
      { slug: 'getting-started', title: 'Getting started', summary: 'Start here.' },
      { slug: 'webhooks', title: 'Webhooks', summary: 'New.' },
    ])
    renderAt('/docs')

    const groups = await screen.findAllByRole('region')
    expect(groups.map((g) => within(g).getByRole('heading').textContent)).toEqual(['Getting started', 'More'])
    expect(within(groups[1]).getByRole('link', { name: /Webhooks/ })).toHaveAttribute('href', '/docs/webhooks')
  })
})

describe('Docs article', () => {
  it('renders the article with its table and section ids', async () => {
    renderAt('/docs/plans-and-limits')

    expect(await screen.findByRole('heading', { level: 1, name: 'Plans and limits' })).toBeInTheDocument()
    const table = screen.getAllByRole('table')[0]
    expect(within(table).getAllByRole('columnheader').length).toBeGreaterThan(1)
    expect(screen.getByRole('heading', { level: 2, name: 'Downgrading' })).toHaveAttribute('id', 'downgrading')
    expect(screen.queryByText(/describes:/)).not.toBeInTheDocument()
  })

  it('renders a code block as preformatted code', async () => {
    renderAt('/docs/heartbeat-monitors')

    await screen.findByRole('heading', { level: 1, name: 'Heartbeat monitors' })
    const block = document.querySelector('pre > code')
    expect(block?.textContent).toContain('/api/ping/')
  })

  it('scrolls to the section a source link names', async () => {
    renderAt('/docs/slack-alerts#setting-it-up')

    const section = await screen.findByRole('heading', { level: 2, name: 'Setting it up' })
    expect(section).toHaveAttribute('id', 'setting-it-up')
    expect(Element.prototype.scrollIntoView).toHaveBeenCalled()
    expect(vi.mocked(Element.prototype.scrollIntoView).mock.contexts[0]).toBe(section)
  })

  it('says so for an article that does not exist', async () => {
    renderAt('/docs/no-such-article')

    expect(await screen.findByRole('heading', { name: 'Article not found' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /All docs/ })).toHaveAttribute('href', '/docs')
  })

  it('invites visitors to sign up at the bottom of an article', async () => {
    renderAt('/docs/slack-alerts')
    expect(await screen.findByRole('link', { name: 'Start free' })).toHaveAttribute('href', '/signup')
  })

  it('opens Ask AI from the bottom of an article when signed in', async () => {
    auth.status = 'authenticated'
    const shown = vi.fn()
    window.addEventListener(SHOW_ASK_AI, shown)
    const ui = renderAt('/docs/slack-alerts')

    await ui.click(await screen.findByRole('button', { name: 'Ask AI' }))
    expect(shown).toHaveBeenCalledOnce()
    expect(screen.getByTestId('app-layout')).toBeInTheDocument()
    window.removeEventListener(SHOW_ASK_AI, shown)
  })
})

describe('Support menu', () => {
  it('links Documentation to the docs; Contact support is still to come', async () => {
    auth.status = 'authenticated'
    const ui = renderAt('/docs')

    await ui.click(screen.getByRole('button', { name: 'Support' }))
    const docs = screen.getByRole('menuitem', { name: /Documentation/ })
    expect(docs).toHaveAttribute('href', '/docs')
    expect(docs).not.toHaveTextContent('Soon')
    expect(screen.getByRole('menuitem', { name: /Contact support/ })).toHaveTextContent('Soon')

    await ui.click(docs)
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  })
})

describe('Section anchors', () => {
  it('follow the backend rule (HelpArticlesTest)', () => {
    expect(helpAnchor('What counts as a failed check')).toBe('what-counts-as-a-failed-check')
    expect(helpAnchor('TIMEOUT: no answer in time')).toBe('timeout-no-answer-in-time')
    expect(helpAnchor('3xx: redirects')).toBe('3xx-redirects')
    expect(helpAnchor('Card, invoices and receipts')).toBe('card-invoices-and-receipts')
    expect(helpAnchor("Addresses you can't monitor")).toBe('addresses-you-cant-monitor')
    expect(helpAnchor('Why it’s late')).toBe('why-its-late')
  })

  it('give every section the backend found in the search eval its id', () => {
    const ids = new Set(
      realSlugs.flatMap((slug) =>
        [...realArticle(slug).markdown.matchAll(/^## (.+)$/gm)].map((m) => `${slug}#${helpAnchor(m[1].trim())}`),
      ),
    )
    const expected = [...evalYaml.matchAll(/^\s*expect: \[(.*)\]$/gm)].flatMap((m) =>
      m[1].split(',').map((s) => s.trim()).filter((s) => s.includes('#')),
    )

    expect(expected.length).toBeGreaterThan(20)
    expect(expected.filter((id) => !ids.has(id))).toEqual([])
  })
})

import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { NotFoundPage } from '../pages/NotFoundPage'
import { ErrorBoundary } from './ErrorBoundary'

function Boom({ message = 'kaboom' }: { message?: string }): never {
  throw new Error(message)
}

beforeEach(() => {
  // React logs caught render errors; they are the point of these tests.
  vi.spyOn(console, 'error').mockImplementation(() => {})
})

describe('ErrorBoundary', () => {
  it('shows a way back instead of a blank page', () => {
    render(
      <ErrorBoundary resetKey="/monitors">
        <Boom />
      </ErrorBoundary>,
    )

    expect(screen.getByRole('alert')).toHaveTextContent('Something went wrong')
    expect(screen.getByRole('button', { name: 'Reload' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to monitors' })).toHaveAttribute('href', '/monitors')
  })

  it('recognises a page file missing after a deploy', () => {
    render(
      <ErrorBoundary resetKey="/billing">
        <Boom message="Failed to fetch dynamically imported module: /assets/BillingPage-abc.js" />
      </ErrorBoundary>,
    )

    expect(screen.getByRole('alert')).toHaveTextContent('PulseGuard was updated')
  })

  it('recovers when the user navigates elsewhere', () => {
    const { rerender } = render(
      <ErrorBoundary resetKey="/billing">
        <Boom />
      </ErrorBoundary>,
    )
    expect(screen.getByRole('alert')).toBeInTheDocument()

    rerender(
      <ErrorBoundary resetKey="/monitors">
        <p>Monitors list</p>
      </ErrorBoundary>,
    )

    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByText('Monitors list')).toBeInTheDocument()
  })
})

describe('NotFoundPage', () => {
  it('says plainly that nothing is here', () => {
    render(
      <MemoryRouter>
        <NotFoundPage />
      </MemoryRouter>,
    )

    expect(screen.getByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
    expect(screen.queryByText(/coming soon/i)).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to monitors' })).toHaveAttribute('href', '/monitors')
  })
})

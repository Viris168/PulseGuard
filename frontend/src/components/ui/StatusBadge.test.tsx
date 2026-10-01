import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { DisplayStatus } from '../../types/monitor'
import { StatusBadge } from './StatusBadge'

describe('StatusBadge', () => {
  // The help docs explain these exact words (getting-started.md; HelpDocsFactsTest checks it).
  it.each<[DisplayStatus, string]>([
    ['UP', 'Up'],
    ['SUSPICIOUS', 'Failing'],
    ['DOWN', 'Down'],
    ['RECOVERING', 'Recovering'],
    ['PENDING', 'Waiting'],
    ['PAUSED', 'Paused'],
  ])('shows %s as "%s"', (status, label) => {
    render(<StatusBadge status={status} />)
    expect(screen.getByText(label)).toBeInTheDocument()
  })
})

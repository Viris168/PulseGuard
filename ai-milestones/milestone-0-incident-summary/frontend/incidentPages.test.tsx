import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api/errors'
import { check, incident, incidentDetail } from '../test/fixtures'
import { IncidentDetailPage } from './IncidentDetailPage'
import { IncidentsPage } from './IncidentsPage'

const api = vi.hoisted(() => ({ listIncidents: vi.fn(), getIncident: vi.fn(), getIncidentSummary: vi.fn() }))
vi.mock('../api/incidents', () => api)
vi.mock('../api/monitors', () => ({ listChecks: vi.fn().mockResolvedValue([check()]), listPings: vi.fn().mockResolvedValue([]) }))

const AI_SUMMARY = 'Payments API returned 503 for 30 minutes.'

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/incidents" element={<IncidentsPage />} />
        <Route path="/incidents/:id" element={<IncidentDetailPage />} />
      </Routes>
    </MemoryRouter>,
  )
  return userEvent.setup()
}

beforeEach(() => {
  api.listIncidents.mockResolvedValue([
    incident({ id: 11, status: 'OPEN', monitorName: 'Search', cause: 'TIMEOUT: No response', resolvedAt: null }),
    incident(),
  ])
  api.getIncident.mockResolvedValue(incidentDetail())
  api.getIncidentSummary.mockResolvedValue({ summary: AI_SUMMARY, generatedAt: new Date().toISOString() })
})

describe('Incidents list', () => {
  it('lists open and resolved incidents with their cause', async () => {
    renderAt('/incidents')

    expect(await screen.findByText('TIMEOUT: No response')).toBeInTheDocument()
    expect(screen.getByText('STATUS_MISMATCH: Expected 200 but got 503')).toBeInTheDocument()
  })

  it('says so when there has never been one', async () => {
    api.listIncidents.mockResolvedValue([])
    renderAt('/incidents')

    expect(await screen.findByText('No incidents yet')).toBeInTheDocument()
  })
})

describe('Incident details', () => {
  it('tells the story: cause, timeline and summary', async () => {
    renderAt('/incidents/10')

    expect(await screen.findByRole('heading', { name: 'Timeline' })).toBeInTheDocument()
    expect(screen.getAllByText(/Expected 200 but got 503/).length).toBeGreaterThan(0)
    expect(await screen.findByText(AI_SUMMARY)).toBeInTheDocument()
  })

  it('labels the summary when the AI model wrote it', async () => {
    renderAt('/incidents/10')

    expect(await screen.findByText(AI_SUMMARY)).toBeInTheDocument()
    expect(screen.getByText('AI-generated')).toBeInTheDocument()
    expect(api.getIncidentSummary).toHaveBeenCalledWith(10)
  })

  it('falls back to the built-in summary, unlabelled, when AI is off', async () => {
    api.getIncidentSummary.mockResolvedValue(null)
    renderAt('/incidents/10')

    expect(await screen.findByText(/alerted email/)).toBeInTheDocument()
    expect(screen.queryByText('AI-generated')).not.toBeInTheDocument()
  })

  it('falls back to the built-in summary when the summary request fails', async () => {
    api.getIncidentSummary.mockRejectedValue(new ApiError(500, 'Server error'))
    renderAt('/incidents/10')

    expect(await screen.findByText(/alerted email/)).toBeInTheDocument()
    expect(screen.queryByText('AI-generated')).not.toBeInTheDocument()
    expect(screen.queryByText('Server error')).not.toBeInTheDocument()
  })

  it("handles another account's incident as not found", async () => {
    api.getIncident.mockRejectedValue(new ApiError(404, 'Incident not found'))
    renderAt('/incidents/999')

    expect(await screen.findByText('Incident not found')).toBeInTheDocument()
  })
})

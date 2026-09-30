import { describe, expect, it } from 'vitest'
import { incidentDetail } from '../test/fixtures'
import { explainCause, ruleBasedSummary } from './incidentSummary'

describe('ruleBasedSummary', () => {
  it('explains the cause, the alerts and the recovery', () => {
    const text = ruleBasedSummary(incidentDetail())

    expect(text).toContain('HTTP 503 means')
    expect(text).toContain('after 3 failed checks')
    expect(text).toContain('alerted email')
    expect(text).toContain('It recovered at')
  })

  it('says what ends a heartbeat incident that is still open', () => {
    const text = ruleBasedSummary(incidentDetail({ monitorType: 'HEARTBEAT', status: 'OPEN', resolvedAt: null }))

    expect(text).toContain('has been down for')
    expect(text).toContain('It resolves on the next ping.')
  })

  it('warns when an alert failed to deliver', () => {
    const text = ruleBasedSummary(
      incidentDetail({
        timeline: [{ type: 'NOTIFIED', at: new Date().toISOString(), event: 'OPENED', channel: 'SLACK', target: 'hooks.slack.com/…', status: 'FAILED' }],
      }),
    )

    expect(text).toContain('The Slack alert failed to deliver')
  })
})

describe('explainCause', () => {
  it('explains DNS failures and leaves unknown causes alone', () => {
    expect(explainCause('DNS: could not resolve host')).toMatch(/hostname didn’t resolve/)
    expect(explainCause('something odd')).toBe('')
  })
})

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { formatDuration, formatStatuses, heartbeatDue, heartbeatSchedule, timeAgo } from './format'

describe('formatStatuses', () => {
  it('reads like the backend failure message', () => {
    expect(formatStatuses([200])).toBe('200')
    expect(formatStatuses([200, 204])).toBe('200 or 204')
    expect(formatStatuses([200, 201, 204])).toBe('200, 201 or 204')
  })
})

describe('formatDuration', () => {
  it('keeps the largest two units', () => {
    expect(formatDuration(45)).toBe('45s')
    expect(formatDuration(90)).toBe('1m 30s')
    expect(formatDuration(3600)).toBe('1h')
    expect(formatDuration(5400)).toBe('1h 30m')
    expect(formatDuration(90_000)).toBe('1d 1h')
  })

  it('rounds before splitting, so 119.9s is not "1m 60s"', () => {
    expect(formatDuration(119.9)).toBe('2m')
  })
})

describe('heartbeat timing', () => {
  const NOW = new Date('2026-01-01T12:00:00Z')

  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(NOW)
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  const pingedAgo = (minutes: number) => new Date(NOW.getTime() - minutes * 60_000).toISOString()
  const hourly = { intervalSeconds: 3600, graceSeconds: 600 }

  it('waits for the first ping', () => {
    expect(heartbeatDue({ ...hourly, lastCheckedAt: null })).toEqual({ text: 'Waiting for first ping', tone: 'waiting' })
  })

  it('is due, then late within grace, then overdue', () => {
    expect(heartbeatDue({ ...hourly, lastCheckedAt: pingedAgo(30) })).toEqual({ text: 'Due in 30m', tone: 'ok' })
    expect(heartbeatDue({ ...hourly, lastCheckedAt: pingedAgo(65) })).toEqual({ text: 'Late 5m (in grace)', tone: 'late' })
    expect(heartbeatDue({ ...hourly, lastCheckedAt: pingedAgo(80) })).toEqual({ text: 'Overdue 20m', tone: 'overdue' })
  })

  it('describes the schedule', () => {
    expect(heartbeatSchedule(hourly)).toBe('every 1h (+10m grace)')
    expect(heartbeatSchedule({ intervalSeconds: 86_400, graceSeconds: null })).toBe('every 1d')
  })

  it('says how long ago', () => {
    expect(timeAgo(null)).toBe('Never')
    expect(timeAgo(pingedAgo(0))).toBe('just now')
    expect(timeAgo(pingedAgo(5))).toBe('5m ago')
    expect(timeAgo(pingedAgo(180))).toBe('3h ago')
  })
})

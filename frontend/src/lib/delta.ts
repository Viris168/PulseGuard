/** A change vs the previous period, ready for a stat card. */
export interface Delta {
  text: string
  direction: 'up' | 'down' | 'flat'
  /** true = this change is good news, false = bad, null = neutral (flat). */
  good: boolean | null
}

function build(diff: number, text: string, higherIsGood: boolean, flat: boolean): Delta {
  if (flat) return { text: 'No change', direction: 'flat', good: null }
  const up = diff > 0
  return { text, direction: up ? 'up' : 'down', good: up === higherIsGood }
}

/** Uptime: the difference in percentage points, e.g. 99.3% vs 100% → "0.70%" down. */
export function pointsDelta(cur: number | null, prev: number | null, higherIsGood = true): Delta | null {
  if (cur === null || prev === null) return null
  const diff = cur - prev
  return build(diff, `${Math.abs(diff).toFixed(2)}%`, higherIsGood, Math.abs(diff) < 0.005)
}

/** Response times: relative change, e.g. 142ms vs 131ms → "8%" up. */
export function relativeDelta(cur: number | null, prev: number | null, higherIsGood = false): Delta | null {
  if (cur === null || prev === null || prev === 0) return null
  const change = ((cur - prev) / prev) * 100
  return build(change, `${Math.round(Math.abs(change))}%`, higherIsGood, Math.abs(change) < 1)
}

/** Counts, e.g. incidents: 3 vs 1 → "2 more". */
export function countDelta(cur: number, prev: number, higherIsGood = false): Delta {
  const diff = cur - prev
  return build(diff, `${Math.abs(diff)} ${diff > 0 ? 'more' : 'fewer'}`, higherIsGood, diff === 0)
}

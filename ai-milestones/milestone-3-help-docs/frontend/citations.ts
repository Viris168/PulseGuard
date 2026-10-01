/**
 * Ask AI cites help-doc sources by number: "[1]", "[1, 3]", "[2][3]" (HelpDocsTools numbers
 * each search result). This splits an answer's text into plain text and citation groups, so the
 * panel can link the numbers that match a source. "[1](…)" is a Markdown link, not a citation.
 */
export type CitationPart = string | { raw: string; numbers: number[] }

const CITATION = /\[(\d{1,2}(?:\s*,\s*\d{1,2})*)\](?!\()/g

export function splitCitations(text: string): CitationPart[] {
  const parts: CitationPart[] = []
  let last = 0
  for (const m of text.matchAll(CITATION)) {
    if (m.index > last) parts.push(text.slice(last, m.index))
    parts.push({ raw: m[0], numbers: m[1].split(',').map((n) => Number(n.trim())) })
    last = m.index + m[0].length
  }
  if (last < text.length) parts.push(text.slice(last))
  return parts
}

/**
 * The sources of one answer, in citation order: each search's results in turn, repeats dropped,
 * the same list the server saves with the answer (ConversationService).
 */
export function mergeSources<T extends { title: string; url: string }>(current: T[], added: T[]): T[] {
  const merged = [...current]
  for (const s of added) {
    if (!merged.some((x) => x.url === s.url && x.title === s.title)) merged.push(s)
  }
  return merged
}

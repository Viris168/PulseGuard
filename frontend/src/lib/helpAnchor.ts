/**
 * A docs section's id, by the same rule as the backend's HelpArticles.anchor, so the source links
 * Ask AI gives ("/docs/slack-alerts#setting-it-up") land on the right heading:
 * "What counts as a failed check" → "what-counts-as-a-failed-check"; "can't" → "cant".
 */
export function helpAnchor(heading: string): string {
  return heading
    .normalize('NFKD')
    .replace(/\p{M}/gu, '')
    .replace(/['’]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')
}

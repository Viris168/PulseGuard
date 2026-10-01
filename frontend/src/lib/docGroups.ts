import type { HelpArticleSummary } from '../api/help'

/** How the list is grouped, in reading order. An article not listed here goes under "More". */
const GROUPS: { title: string; slugs: string[] }[] = [
  { title: 'Getting started', slugs: ['getting-started', 'how-incidents-work'] },
  {
    title: 'Monitors and status pages',
    slugs: ['http-monitors', 'heartbeat-monitors', 'status-codes', 'troubleshooting-checks', 'history-and-retention', 'status-pages'],
  },
  { title: 'Alerts', slugs: ['email-alerts', 'slack-alerts', 'alert-delivery'] },
  { title: 'Account and billing', slugs: ['account', 'plans-and-limits', 'billing', 'api-keys'] },
  { title: 'Ask AI', slugs: ['ask-ai'] },
]

export function groupArticles(articles: HelpArticleSummary[]): { title: string; articles: HelpArticleSummary[] }[] {
  const bySlug = new Map(articles.map((a) => [a.slug, a]))
  const groups = GROUPS.map((g) => ({
    title: g.title,
    articles: g.slugs.flatMap((s) => {
      const a = bySlug.get(s)
      bySlug.delete(s)
      return a ? [a] : []
    }),
  }))
  groups.push({ title: 'More', articles: [...bySlug.values()] })
  return groups.filter((g) => g.articles.length > 0)
}

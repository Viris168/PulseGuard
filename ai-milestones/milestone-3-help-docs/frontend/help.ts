/**
 * Help docs API — live against HelpController. Public, like the status page data: sent without a
 * token so the /docs page works signed out and a stale session can't get in the way.
 */
import { api } from './http'

/** One entry of GET /api/help. */
export interface HelpArticleSummary {
  slug: string
  title: string
  summary: string
}

/** GET /api/help/{slug}: `markdown` is the article without its front matter. */
export interface HelpArticle extends HelpArticleSummary {
  markdown: string
}

export async function listHelpArticles(): Promise<HelpArticleSummary[]> {
  return api<HelpArticleSummary[]>('/api/help', { auth: false })
}

/** 404 (ApiError) for a slug that isn't an article. */
export async function getHelpArticle(slug: string): Promise<HelpArticle> {
  return api<HelpArticle>(`/api/help/${encodeURIComponent(slug)}`, { auth: false })
}

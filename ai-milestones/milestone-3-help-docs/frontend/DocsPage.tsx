import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ChevronRight } from 'lucide-react'
import { listHelpArticles, type HelpArticleSummary } from '../../api/help'
import { groupArticles } from '../../lib/docGroups'
import { PageHeader } from '../../components/layout/PageHeader'
import { Spinner } from '../../components/ui/Spinner'

export function DocsPage() {
  const [articles, setArticles] = useState<HelpArticleSummary[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    listHelpArticles()
      .then(setArticles)
      .catch((e: unknown) => setError(e instanceof Error ? e.message : 'Could not load the docs'))
  }, [])

  useEffect(() => {
    document.title = 'Documentation · PulseGuard'
    return () => {
      document.title = 'PulseGuard'
    }
  }, [])

  return (
    <>
      <PageHeader title="Documentation" description="How PulseGuard works: monitors, incidents, alerts and your account." />
      {!articles ? (
        <div className="flex justify-center py-24 text-zinc-400">{error ? <p className="text-sm">{error}</p> : <Spinner />}</div>
      ) : (
        <div className="space-y-8">
          {groupArticles(articles).map((g) => (
            <section key={g.title} aria-labelledby={`group-${g.title}`}>
              <h2 id={`group-${g.title}`} className="mb-3 text-xs font-semibold tracking-wide text-zinc-500 uppercase dark:text-zinc-400">
                {g.title}
              </h2>
              <ul className="grid gap-3 sm:grid-cols-2">
                {g.articles.map((a) => (
                  <li key={a.slug}>
                    <Link
                      to={`/docs/${a.slug}`}
                      className="group flex h-full items-start gap-3 rounded-xl border border-zinc-200 bg-white p-4 shadow-sm transition-colors hover:border-emerald-500/60 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-emerald-600 dark:border-zinc-800 dark:bg-zinc-900 dark:hover:border-emerald-500/50"
                    >
                      <div className="min-w-0 flex-1">
                        <p className="font-medium text-zinc-900 dark:text-white">{a.title}</p>
                        <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">{a.summary}</p>
                      </div>
                      <ChevronRight className="mt-0.5 size-4 shrink-0 text-zinc-400 transition-transform group-hover:translate-x-0.5" aria-hidden />
                    </Link>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </div>
      )}
    </>
  )
}

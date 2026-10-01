import { useEffect, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import { SearchX, Sparkles } from 'lucide-react'
import { ApiError } from '../../api/errors'
import { getHelpArticle, type HelpArticle } from '../../api/help'
import { useAuth } from '../../auth/authContext'
import { BackLink } from '../../components/ui/BackLink'
import { Card } from '../../components/ui/Card'
import { Markdown } from '../../components/ui/Markdown'
import { Spinner } from '../../components/ui/Spinner'
import { showAskAi } from '../../lib/events'

export function DocArticlePage() {
  const { slug = '' } = useParams()
  const { hash } = useLocation()
  // Keyed by slug, so moving to another article never shows the previous one's text.
  const [loaded, setLoaded] = useState<{ slug: string; article: HelpArticle | null; error: string | null } | null>(null)

  useEffect(() => {
    let current = true
    getHelpArticle(slug)
      .then((article) => current && setLoaded({ slug, article, error: null }))
      .catch((e: unknown) => {
        if (!current) return
        const error = e instanceof ApiError && e.status === 404 ? 'not-found' : e instanceof Error ? e.message : 'Could not load the article'
        setLoaded({ slug, article: null, error })
      })
    return () => {
      current = false
    }
  }, [slug])

  const article = loaded?.slug === slug ? loaded.article : null
  const error = loaded?.slug === slug ? loaded.error : null

  // A source link ("#setting-it-up") scrolls to its section once the text is on the page.
  useEffect(() => {
    if (!article || !hash) return
    document.getElementById(decodeURIComponent(hash.slice(1)))?.scrollIntoView()
  }, [article, hash])

  useEffect(() => {
    if (!article) return
    document.title = `${article.title} · PulseGuard docs`
    return () => {
      document.title = 'PulseGuard'
    }
  }, [article])

  if (error === 'not-found') {
    return (
      <>
        <BackLink to="/docs">All docs</BackLink>
        <Card className="flex flex-col items-center px-6 py-16 text-center">
          <SearchX className="size-10 text-zinc-400" aria-hidden />
          <h1 className="mt-4 text-xl font-semibold">Article not found</h1>
          <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">It may have been renamed. The full list is on the docs page.</p>
        </Card>
      </>
    )
  }

  if (!article) {
    return <div className="flex justify-center py-24 text-zinc-400">{error ? <p className="text-sm">{error}</p> : <Spinner />}</div>
  }

  return (
    <article className="mx-auto max-w-3xl">
      <BackLink to="/docs">All docs</BackLink>
      <header className="mb-6">
        <h1 className="text-2xl font-semibold tracking-tight">{article.title}</h1>
        <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">{article.summary}</p>
      </header>
      <Card className="px-5 py-6 sm:px-8">
        <Markdown anchors className="text-[15px]">
          {article.markdown}
        </Markdown>
      </Card>
      <StillStuck />
    </article>
  )
}

/** Ask AI for signed-in users; for visitors, a reason to sign up. */
function StillStuck() {
  const { status } = useAuth()
  return (
    <div className="mt-6 flex flex-col gap-3 rounded-xl border border-dashed border-zinc-300 p-4 text-sm sm:flex-row sm:items-center dark:border-zinc-700">
      <Sparkles className="size-5 shrink-0 text-emerald-600 dark:text-emerald-400" aria-hidden />
      {status === 'authenticated' ? (
        <>
          <p className="flex-1 text-zinc-600 dark:text-zinc-400">Didn't find your answer? Ask AI knows these docs and your monitors.</p>
          <button
            type="button"
            onClick={showAskAi}
            className="rounded-lg bg-emerald-600 px-3.5 py-2 font-medium text-white hover:bg-emerald-700"
          >
            Ask AI
          </button>
        </>
      ) : (
        <>
          <p className="flex-1 text-zinc-600 dark:text-zinc-400">
            Signed-in users can ask questions like this one, answered from these docs and their own monitors.
          </p>
          <Link to="/signup" className="rounded-lg bg-emerald-600 px-3.5 py-2 text-center font-medium text-white hover:bg-emerald-700">
            Start free
          </Link>
        </>
      )}
    </div>
  )
}

import { Link, Outlet } from 'react-router-dom'
import { PageBoundary } from '../PageBoundary'
import { Logo } from './Logo'

/** The docs for visitors: the landing page's top bar instead of the app's sidebar. */
export function PublicDocsLayout() {
  return (
    <div className="min-h-dvh">
      <header className="sticky top-0 z-30 border-b border-zinc-200/70 bg-white/80 backdrop-blur dark:border-zinc-800 dark:bg-zinc-950/80">
        <div className="mx-auto flex h-16 max-w-6xl items-center gap-6 px-4 sm:px-6">
          <Logo />
          <Link to="/docs" className="hidden text-sm text-zinc-600 hover:text-zinc-900 sm:block dark:text-zinc-400 dark:hover:text-white">
            Documentation
          </Link>
          <div className="ml-auto flex items-center gap-2">
            <Link to="/login" className="rounded-lg px-3 py-2 text-sm font-medium text-zinc-700 hover:bg-zinc-100 dark:text-zinc-300 dark:hover:bg-zinc-800">
              Sign in
            </Link>
            <Link to="/signup" className="rounded-lg bg-emerald-600 px-3.5 py-2 text-sm font-medium text-white hover:bg-emerald-700">
              Get started
            </Link>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-6 sm:px-6 lg:py-8">
        <PageBoundary>
          <Outlet />
        </PageBoundary>
      </main>
    </div>
  )
}

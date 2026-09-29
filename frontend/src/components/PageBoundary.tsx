import { Suspense, type ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import { ErrorBoundary } from './ErrorBoundary'
import { Spinner } from './ui/Spinner'

/**
 * Around every routed page: a spinner while the page's code loads (pages are lazy), and an
 * error screen instead of a blank app if it crashes. Keyed by path, so navigating elsewhere
 * recovers. Placed inside the layouts, so the navigation stays usable either way.
 */
export function PageBoundary({ children }: { children: ReactNode }) {
  const { pathname } = useLocation()
  return (
    <ErrorBoundary resetKey={pathname}>
      <Suspense
        fallback={
          <div className="flex justify-center py-24 text-zinc-400">
            <Spinner />
          </div>
        }
      >
        {children}
      </Suspense>
    </ErrorBoundary>
  )
}

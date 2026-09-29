import { Component, type ErrorInfo, type ReactNode } from 'react'
import { AlertTriangle, RotateCw } from 'lucide-react'

interface Props {
  children: ReactNode
  /** When this changes (e.g. the path), a crashed page is given another chance. */
  resetKey?: string
}

interface State {
  error: Error | null
  resetKey?: string
}

/**
 * A page that throws while rendering shows this instead of blanking the whole app. It also
 * catches a lazily loaded page whose file is gone after a deploy (an open tab asking for an old
 * chunk): reloading fetches the new build.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null, resetKey: this.props.resetKey }

  static getDerivedStateFromError(error: Error): Partial<State> {
    return { error }
  }

  /** A new resetKey (the user navigated elsewhere) clears the error before rendering. */
  static getDerivedStateFromProps(props: Props, state: State): Partial<State> | null {
    return props.resetKey !== state.resetKey ? { error: null, resetKey: props.resetKey } : null
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Page crashed', error, info.componentStack)
  }

  render() {
    const { error } = this.state
    if (!error) return this.props.children
    const staleBuild = isChunkLoadError(error)
    return (
      <div role="alert" className="mx-auto flex max-w-md flex-col items-center px-6 py-16 text-center">
        <div className="flex size-12 items-center justify-center rounded-full bg-red-50 dark:bg-red-500/10">
          <AlertTriangle className="size-6 text-red-600 dark:text-red-400" aria-hidden />
        </div>
        <h2 className="mt-4 text-base font-semibold">{staleBuild ? 'PulseGuard was updated' : 'Something went wrong'}</h2>
        <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
          {staleBuild
            ? 'Reload to get the latest version. Nothing you saved is lost.'
            : 'This page hit an error. Reloading usually fixes it; your data is safe.'}
        </p>
        <div className="mt-5 flex gap-2">
          <button
            type="button"
            onClick={() => window.location.reload()}
            className="inline-flex items-center gap-1.5 rounded-lg bg-emerald-600 px-3.5 py-2 text-sm font-medium text-white hover:bg-emerald-700"
          >
            <RotateCw className="size-4" aria-hidden />
            Reload
          </button>
          <a
            href="/monitors"
            className="inline-flex items-center rounded-lg border border-zinc-300 px-3.5 py-2 text-sm font-medium hover:bg-zinc-50 dark:border-zinc-700 dark:hover:bg-zinc-800"
          >
            Go to monitors
          </a>
        </div>
      </div>
    )
  }
}

/** Browsers word this differently; all mean "the page's script file could not be loaded". */
function isChunkLoadError(error: Error): boolean {
  return /dynamically imported module|Importing a module script failed|error loading dynamically imported module|Loading chunk/i.test(
    error.message,
  )
}

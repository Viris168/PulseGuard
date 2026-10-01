import { Children, isValidElement, type ReactNode } from 'react'
import ReactMarkdown, { type Components } from 'react-markdown'
import { Link } from 'react-router-dom'
import remarkGfm from 'remark-gfm'
import { cn } from '../../lib/format'
import { helpAnchor } from '../../lib/helpAnchor'

/** The plain text of rendered children, e.g. a heading's, for its id. */
function textOf(node: ReactNode): string {
  if (typeof node === 'string' || typeof node === 'number') return String(node)
  if (isValidElement<{ children?: ReactNode }>(node)) return textOf(node.props.children)
  return Children.toArray(node).map(textOf).join('')
}

const base: Components = {
  h2: ({ children }) => <h2 className="mt-8 mb-3 text-lg font-semibold tracking-tight">{children}</h2>,
  h3: ({ children }) => <h3 className="mt-6 mb-2 font-semibold">{children}</h3>,
  p: ({ children }) => <p className="my-3 leading-relaxed">{children}</p>,
  ul: ({ children }) => <ul className="my-3 list-disc space-y-1.5 pl-6">{children}</ul>,
  ol: ({ children }) => <ol className="my-3 list-decimal space-y-1.5 pl-6">{children}</ol>,
  li: ({ children }) => <li className="pl-1 leading-relaxed">{children}</li>,
  strong: ({ children }) => <strong className="font-semibold text-zinc-900 dark:text-white">{children}</strong>,
  blockquote: ({ children }) => (
    <blockquote className="my-3 border-l-2 border-zinc-300 pl-4 text-zinc-600 dark:border-zinc-700 dark:text-zinc-400">{children}</blockquote>
  ),
  code: ({ children }) => (
    <code className="rounded bg-zinc-100 px-1 py-0.5 font-mono text-[0.875em] text-zinc-900 dark:bg-zinc-800 dark:text-zinc-100">{children}</code>
  ),
  // Inside a block, the inline code style is reset so the block's own colours show.
  pre: ({ children }) => (
    <pre className="my-4 overflow-x-auto rounded-lg bg-zinc-950 p-4 text-sm leading-relaxed text-zinc-100 dark:border dark:border-zinc-800 [&_code]:bg-transparent [&_code]:p-0 [&_code]:text-inherit">
      {children}
    </pre>
  ),
  // Scrolls sideways on a phone instead of squeezing the columns.
  table: ({ children }) => (
    <div className="my-4 overflow-x-auto rounded-lg border border-zinc-200 dark:border-zinc-800">
      <table className="w-full border-collapse text-left text-sm">{children}</table>
    </div>
  ),
  thead: ({ children }) => <thead className="bg-zinc-50 dark:bg-zinc-800/50">{children}</thead>,
  th: ({ children }) => <th className="border-b border-zinc-200 px-3 py-2 font-semibold whitespace-nowrap dark:border-zinc-800">{children}</th>,
  td: ({ children }) => <td className="border-t border-zinc-100 px-3 py-2 align-top dark:border-zinc-800">{children}</td>,
  hr: () => <hr className="my-6 border-zinc-200 dark:border-zinc-800" />,
}

const linkClass = 'font-medium text-emerald-700 underline-offset-2 hover:underline dark:text-emerald-400'

/** Links inside the app stay in the app; anything else opens in a new tab. */
const withLinks: Components = {
  a: ({ href = '', children }) =>
    href.startsWith('/') ? (
      <Link to={href} className={linkClass}>
        {children}
      </Link>
    ) : (
      <a href={href} className={linkClass} target={href.startsWith('#') ? undefined : '_blank'} rel="noreferrer">
        {children}
      </a>
    ),
}

/** Section headings get the backend's anchors as ids, so "/docs/x#section" scrolls to them. */
const withAnchors: Components = {
  h2: ({ children }) => (
    <h2 id={helpAnchor(textOf(children))} className="mt-8 mb-3 scroll-mt-24 text-lg font-semibold tracking-tight">
      {children}
    </h2>
  ),
}

interface Props {
  children: string
  /** Give `##` headings ids (the docs page). */
  anchors?: boolean
  className?: string
}

/**
 * Markdown with GitHub tables, styled for the app in light and dark. Raw HTML in the text is not
 * rendered (react-markdown's default), so it is safe for text the app didn't write.
 */
export function Markdown({ children, anchors = false, className }: Props) {
  return (
    <div className={cn('text-sm text-zinc-700 dark:text-zinc-300 [&>:first-child]:mt-0 [&>:last-child]:mb-0', className)}>
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={{ ...base, ...withLinks, ...(anchors ? withAnchors : {}) }}>
        {children}
      </ReactMarkdown>
    </div>
  )
}

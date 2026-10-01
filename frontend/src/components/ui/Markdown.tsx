import { Children, isValidElement, type ReactNode } from 'react'
import ReactMarkdown, { type Components } from 'react-markdown'
import { Link } from 'react-router-dom'
import remarkGfm from 'remark-gfm'
import { splitCitations } from '../../lib/citations'
import { cn } from '../../lib/format'
import { helpAnchor } from '../../lib/helpAnchor'

/** The plain text of rendered children, e.g. a heading's, for its id. */
function textOf(node: ReactNode): string {
  if (typeof node === 'string' || typeof node === 'number') return String(node)
  if (isValidElement<{ children?: ReactNode }>(node)) return textOf(node.props.children)
  return Children.toArray(node).map(textOf).join('')
}

/** Just enough of a Markdown syntax tree node for the citations plugin. */
interface MdNode {
  type: string
  value?: string
  children?: MdNode[]
  data?: { hName?: string; hProperties?: Record<string, string> }
}

/**
 * Turns "[1]" and "[1, 3]" in the text into <cite> elements, which the `cite` component renders.
 * Text inside links is left alone ("[1](…)" is a link), and so is code, which isn't a text node.
 * The model can't write a <cite> itself: raw HTML is never rendered.
 */
function remarkCitations() {
  const split = (node: MdNode) => {
    if (!node.children || node.type === 'link' || node.type === 'linkReference') return
    node.children = node.children.flatMap((child): MdNode[] => {
      if (child.type !== 'text') {
        split(child)
        return [child]
      }
      return splitCitations(child.value ?? '').map((part) =>
        typeof part === 'string'
          ? { type: 'text', value: part }
          : { type: 'citation', data: { hName: 'cite', hProperties: { dataRaw: part.raw } }, children: [{ type: 'text', value: part.raw }] },
      )
    })
  }
  return (tree: MdNode) => split(tree)
}

const linkClass = 'font-medium text-emerald-700 underline-offset-2 hover:underline dark:text-emerald-400'

interface Props {
  children: string
  /** Give `##` headings ids (the docs page). */
  anchors?: boolean
  /**
   * false for text the app didn't write (Ask AI's answers): links show as plain text and images
   * as their alt text, so an answer can never send anyone, or load anything, somewhere the model made up.
   */
  links?: boolean
  /** Tighter spacing, for a chat bubble. */
  compact?: boolean
  /** Renders a citation such as "[1, 3]"; without it, citations stay as written. */
  renderCitation?: (numbers: number[], raw: string) => ReactNode
  className?: string
}

/**
 * Markdown with GitHub tables, styled for the app in light and dark. Raw HTML in the text is not
 * rendered (react-markdown's default), so it is safe for text the app didn't write.
 */
export function Markdown({ children, anchors = false, links = true, compact = false, renderCitation, className }: Props) {
  const block = compact ? 'my-2' : 'my-3'
  const h2Class = cn(compact ? 'mt-4 mb-2 text-base' : 'mt-8 mb-3 text-lg', 'font-semibold tracking-tight')
  const components: Components = {
    h2: ({ children }) =>
      anchors ? (
        // The backend's anchors as ids, so "/docs/x#section" scrolls to the section.
        <h2 id={helpAnchor(textOf(children))} className={cn(h2Class, 'scroll-mt-24')}>
          {children}
        </h2>
      ) : (
        <h2 className={h2Class}>{children}</h2>
      ),
    h3: ({ children }) => <h3 className={cn(compact ? 'mt-3 mb-1.5' : 'mt-6 mb-2', 'font-semibold')}>{children}</h3>,
    // In a chat bubble a single line break is kept, as the model meant it.
    p: ({ children }) => <p className={cn(block, 'leading-relaxed', compact && 'whitespace-pre-line')}>{children}</p>,
    ul: ({ children }) => <ul className={cn(block, 'list-disc pl-6', compact ? 'space-y-0.5' : 'space-y-1.5')}>{children}</ul>,
    ol: ({ children }) => <ol className={cn(block, 'list-decimal pl-6', compact ? 'space-y-0.5' : 'space-y-1.5')}>{children}</ol>,
    li: ({ children }) => <li className="pl-1 leading-relaxed">{children}</li>,
    strong: ({ children }) => <strong className="font-semibold text-zinc-900 dark:text-white">{children}</strong>,
    blockquote: ({ children }) => (
      <blockquote className={cn(block, 'border-l-2 border-zinc-300 pl-4 text-zinc-600 dark:border-zinc-700 dark:text-zinc-400')}>{children}</blockquote>
    ),
    code: ({ children }) => (
      <code className="rounded bg-zinc-100 px-1 py-0.5 font-mono text-[0.875em] break-words text-zinc-900 dark:bg-zinc-800 dark:text-zinc-100">
        {children}
      </code>
    ),
    // Inside a block, the inline code style is reset so the block's own colours show.
    pre: ({ children }) => (
      <pre
        className={cn(
          compact ? 'my-2 p-3 text-xs' : 'my-4 p-4 text-sm',
          'overflow-x-auto rounded-lg bg-zinc-950 leading-relaxed text-zinc-100 dark:border dark:border-zinc-800 [&_code]:bg-transparent [&_code]:p-0 [&_code]:break-normal [&_code]:text-inherit',
        )}
      >
        {children}
      </pre>
    ),
    // Scrolls sideways on a phone instead of squeezing the columns.
    table: ({ children }) => (
      <div className={cn(compact ? 'my-2' : 'my-4', 'overflow-x-auto rounded-lg border border-zinc-200 dark:border-zinc-800')}>
        <table className="w-full border-collapse text-left text-sm">{children}</table>
      </div>
    ),
    thead: ({ children }) => <thead className="bg-zinc-50 dark:bg-zinc-800/50">{children}</thead>,
    th: ({ children }) => <th className="border-b border-zinc-200 px-3 py-2 font-semibold whitespace-nowrap dark:border-zinc-800">{children}</th>,
    td: ({ children }) => <td className="border-t border-zinc-100 px-3 py-2 align-top dark:border-zinc-800">{children}</td>,
    hr: () => <hr className="my-6 border-zinc-200 dark:border-zinc-800" />,
    // Links inside the app stay in the app; anything else opens in a new tab.
    a: ({ href = '', children }) =>
      !links ? (
        <span>{children}</span>
      ) : href.startsWith('/') ? (
        <Link to={href} className={linkClass}>
          {children}
        </Link>
      ) : (
        <a href={href} className={linkClass} target={href.startsWith('#') ? undefined : '_blank'} rel="noreferrer">
          {children}
        </a>
      ),
    img: ({ src, alt }) => (links && typeof src === 'string' ? <img src={src} alt={alt ?? ''} className="my-3 max-w-full rounded-lg" /> : <span>{alt}</span>),
    cite: ({ node, children }) => {
      const raw = String(node?.properties?.dataRaw ?? '')
      const part = splitCitations(raw)[0]
      return renderCitation && part && typeof part !== 'string' ? <>{renderCitation(part.numbers, raw)}</> : <>{children}</>
    },
  }

  return (
    <div className={cn('text-sm text-zinc-700 dark:text-zinc-300 [&>:first-child]:mt-0 [&>:last-child]:mb-0', className)}>
      <ReactMarkdown remarkPlugins={renderCitation ? [remarkGfm, remarkCitations] : [remarkGfm]} components={components}>
        {children}
      </ReactMarkdown>
    </div>
  )
}

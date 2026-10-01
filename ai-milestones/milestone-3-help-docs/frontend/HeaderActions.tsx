import { BookOpen, CircleHelp, LifeBuoy, Sparkles, type LucideIcon } from 'lucide-react'
import { Link } from 'react-router-dom'
import { HeaderMenu, headerTextButton } from './HeaderMenu'

// An item without `to` is a placeholder until that feature exists, shown with a "Soon" tag.
const supportItems: { icon: LucideIcon; label: string; hint: string; to?: string }[] = [
  { icon: BookOpen, label: 'Documentation', hint: 'Guides for monitors, incidents and alerts', to: '/docs' },
  { icon: LifeBuoy, label: 'Contact support', hint: 'Get help from the PulseGuard team' },
]

function Soon() {
  return (
    <span className="rounded-full bg-zinc-100 px-1.5 py-0.5 text-[10px] font-medium text-zinc-500 uppercase dark:bg-zinc-800 dark:text-zinc-400">
      Soon
    </span>
  )
}

/** Toggles the Ask AI panel, which AppLayout owns so the page can make room for it. */
export function AskAiButton({ open, onToggle }: { open: boolean; onToggle: () => void }) {
  return (
    <button type="button" onClick={onToggle} aria-expanded={open} className={headerTextButton}>
      <Sparkles className="size-4" aria-hidden />
      <span className="hidden sm:inline">Ask AI</span>
    </button>
  )
}

export function SupportMenu() {
  return (
    <HeaderMenu
      triggerLabel="Support"
      triggerClassName={headerTextButton}
      trigger={() => (
        <>
          <CircleHelp className="size-4" aria-hidden />
          <span className="hidden sm:inline">Support</span>
        </>
      )}
      panelClassName="w-72 py-1"
    >
      {(close) => (
        <ul role="menu">
          {supportItems.map(({ icon: Icon, label, hint, to }) => {
            const body = (
              <>
                <Icon className="mt-0.5 size-4 shrink-0 text-zinc-400" aria-hidden />
                <div className="min-w-0 flex-1">
                  <p className="flex items-center gap-2 text-sm font-medium text-zinc-700 dark:text-zinc-300">
                    {label} {!to && <Soon />}
                  </p>
                  <p className="text-xs text-zinc-500 dark:text-zinc-400">{hint}</p>
                </div>
              </>
            )
            return to ? (
              <li key={label} role="none">
                <Link
                  to={to}
                  role="menuitem"
                  onClick={close}
                  className="flex items-start gap-3 px-4 py-2.5 hover:bg-zinc-50 focus-visible:bg-zinc-50 focus-visible:outline-none dark:hover:bg-zinc-800/60 dark:focus-visible:bg-zinc-800/60"
                >
                  {body}
                </Link>
              </li>
            ) : (
              <li key={label} role="menuitem" aria-disabled className="flex cursor-not-allowed items-start gap-3 px-4 py-2.5">
                {body}
              </li>
            )
          })}
        </ul>
      )}
    </HeaderMenu>
  )
}

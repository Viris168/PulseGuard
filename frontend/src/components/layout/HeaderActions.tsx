import { BookOpen, CircleHelp, LifeBuoy, Sparkles, type LucideIcon } from 'lucide-react'
import { HeaderMenu, headerTextButton } from './HeaderMenu'

// Placeholders until these features exist; the buttons are here so the top bar layout is final.
const supportItems: { icon: LucideIcon; label: string; hint: string }[] = [
  { icon: BookOpen, label: 'Documentation', hint: 'Guides for monitors, incidents and alerts' },
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
      {() => (
        <ul role="menu">
          {supportItems.map(({ icon: Icon, label, hint }) => (
            <li key={label} role="menuitem" aria-disabled className="flex cursor-not-allowed items-start gap-3 px-4 py-2.5">
              <Icon className="mt-0.5 size-4 shrink-0 text-zinc-400" aria-hidden />
              <div className="min-w-0 flex-1">
                <p className="flex items-center gap-2 text-sm font-medium text-zinc-700 dark:text-zinc-300">
                  {label} <Soon />
                </p>
                <p className="text-xs text-zinc-500 dark:text-zinc-400">{hint}</p>
              </div>
            </li>
          ))}
        </ul>
      )}
    </HeaderMenu>
  )
}

import { useEffect, useRef, useState, type ReactNode } from 'react'
import { cn } from '../../lib/format'

interface Props {
  /** Renders the trigger; gets whether the menu is open. */
  trigger: (open: boolean) => ReactNode
  triggerLabel: string
  triggerClassName?: string
  panelClassName?: string
  /** Panel content; call `close` from links or actions inside it. */
  children: (close: () => void) => ReactNode
}

/** Top-bar dropdown: closes on outside click and Escape. */
export function HeaderMenu({ trigger, triggerLabel, triggerClassName, panelClassName, children }: Props) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    const onPointerDown = (e: PointerEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false)
    }
    const onKeyDown = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false)
    document.addEventListener('pointerdown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('pointerdown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-label={triggerLabel}
        aria-haspopup="menu"
        aria-expanded={open}
        className={triggerClassName}
      >
        {trigger(open)}
      </button>
      {open && (
        <div
          className={cn(
            'absolute right-0 z-40 mt-2 overflow-hidden rounded-xl border border-zinc-200 bg-white shadow-lg dark:border-zinc-800 dark:bg-zinc-900',
            panelClassName,
          )}
        >
          {children(() => setOpen(false))}
        </div>
      )}
    </div>
  )
}

/** Text + icon button used across the top bar (Ask AI, Support). Label hides on small screens. */
export const headerTextButton =
  'flex h-9 items-center gap-1.5 rounded-md px-2 text-sm font-medium whitespace-nowrap text-zinc-600 transition-colors hover:bg-zinc-100 hover:text-zinc-900 aria-expanded:bg-zinc-100 aria-expanded:text-zinc-900 sm:px-2.5 dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-white dark:aria-expanded:bg-zinc-800 dark:aria-expanded:text-white'

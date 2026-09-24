import { Link } from 'react-router-dom'
import { LogOut, Settings } from 'lucide-react'
import { PLAN_LABEL, type User } from '../../types/auth'
import { HeaderMenu } from './HeaderMenu'

const item =
  'flex w-full items-center gap-2.5 px-4 py-2 text-left text-sm text-zinc-700 hover:bg-zinc-100 dark:text-zinc-300 dark:hover:bg-zinc-800'

/** Avatar button in the top bar; the account details live in its dropdown. */
export function ProfileMenu({ user, onSignOut }: { user: User; onSignOut: () => void }) {
  return (
    <HeaderMenu
      triggerLabel="Account menu"
      triggerClassName="flex size-8 items-center justify-center rounded-full bg-emerald-600 text-xs font-semibold text-white ring-offset-2 transition hover:bg-emerald-700 focus-visible:ring-2 focus-visible:ring-emerald-600 focus-visible:outline-none dark:ring-offset-zinc-950"
      trigger={() => (user.name.trim()[0] ?? user.email[0]).toUpperCase()}
      panelClassName="w-64 py-1"
    >
      {(close) => (
        <div role="menu">
          <div className="border-b border-zinc-200 px-4 py-3 dark:border-zinc-800">
            <p className="truncate text-sm font-medium">{user.name}</p>
            <p className="truncate text-xs text-zinc-500 dark:text-zinc-400">{user.email}</p>
            <p className="mt-1.5 inline-flex rounded-full bg-emerald-50 px-2 py-0.5 text-xs font-medium text-emerald-700 dark:bg-emerald-500/10 dark:text-emerald-400">
              {PLAN_LABEL[user.plan]} plan
            </p>
          </div>
          <Link to="/settings" role="menuitem" onClick={close} className={item}>
            <Settings className="size-4" aria-hidden />
            Settings
          </Link>
          <button
            type="button"
            role="menuitem"
            onClick={() => {
              close()
              onSignOut()
            }}
            className={item}
          >
            <LogOut className="size-4" aria-hidden />
            Sign out
          </button>
        </div>
      )}
    </HeaderMenu>
  )
}

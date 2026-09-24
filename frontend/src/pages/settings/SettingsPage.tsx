import { useSearchParams } from 'react-router-dom'
import { Bell, KeyRound, Shield, UserRound, type LucideIcon } from 'lucide-react'
import { cn } from '../../lib/format'
import { PageHeader } from '../../components/layout/PageHeader'
import { ApiKeysSection } from './ApiKeysSection'
import { ChannelsSection } from './ChannelsSection'
import { ProfileSection } from './ProfileSection'
import { SecuritySection } from './SecuritySection'

type Tab = 'profile' | 'security' | 'channels' | 'api-keys'

const TABS: { key: Tab; label: string; icon: LucideIcon }[] = [
  { key: 'profile', label: 'Profile', icon: UserRound },
  { key: 'security', label: 'Security', icon: Shield },
  { key: 'channels', label: 'Alert channels', icon: Bell },
  { key: 'api-keys', label: 'API keys', icon: KeyRound },
]

export function SettingsPage() {
  const [params, setParams] = useSearchParams()
  const tab = (TABS.some((t) => t.key === params.get('tab')) ? params.get('tab') : 'profile') as Tab

  return (
    <>
      <PageHeader title="Settings" description="Your account, sign-in security, and where alerts go." />

      <div className="flex flex-col gap-6 lg:flex-row">
        <nav className="flex gap-1 overflow-x-auto lg:w-52 lg:shrink-0 lg:flex-col" aria-label="Settings sections">
          {TABS.map(({ key, label, icon: Icon }) => (
            <button
              key={key}
              onClick={() => setParams(key === 'profile' ? {} : { tab: key }, { replace: true })}
              aria-current={tab === key ? 'page' : undefined}
              className={cn(
                'flex items-center gap-2.5 rounded-lg px-3 py-2 text-sm font-medium whitespace-nowrap transition-colors',
                tab === key
                  ? 'bg-zinc-900 text-white lg:bg-zinc-100 lg:text-zinc-900 dark:bg-white dark:text-zinc-900 lg:dark:bg-zinc-800 lg:dark:text-white'
                  : 'text-zinc-600 hover:bg-zinc-100 hover:text-zinc-900 dark:text-zinc-400 dark:hover:bg-zinc-800 dark:hover:text-white',
              )}
            >
              <Icon className="size-4" aria-hidden />
              {label}
            </button>
          ))}
        </nav>

        <div className="min-w-0 flex-1">
          {tab === 'profile' && <ProfileSection />}
          {tab === 'security' && <SecuritySection />}
          {tab === 'channels' && <ChannelsSection />}
          {tab === 'api-keys' && <ApiKeysSection />}
        </div>
      </div>
    </>
  )
}

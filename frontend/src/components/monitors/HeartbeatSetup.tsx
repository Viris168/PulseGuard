import { useState } from 'react'
import { Check, Copy, Send } from 'lucide-react'
import type { Monitor } from '../../types/monitor'
import { cn, formatDuration } from '../../lib/format'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'

type Snippet = 'curl' | 'cron' | 'bash'

function snippets(url: string, intervalSeconds: number): Record<Snippet, string> {
  // A cron line that roughly matches the expected period.
  const cron =
    intervalSeconds >= 86_400 ? '0 3 * * *' : intervalSeconds >= 3600 ? '0 * * * *' : `*/${Math.max(1, Math.round(intervalSeconds / 60))} * * * *`
  return {
    curl: `curl -fsS -m 10 --retry 3 ${url}`,
    cron: `${cron}  /path/to/your-job.sh && curl -fsS -m 10 --retry 3 ${url} > /dev/null`,
    bash: `#!/usr/bin/env bash\nset -euo pipefail\n\n# ... your job ...\n\n# Only reached if everything above succeeded.\ncurl -fsS -m 10 --retry 3 ${url} > /dev/null`,
  }
}

function CopyButton({ text, label }: { text: string; label: string }) {
  const [copied, setCopied] = useState(false)
  return (
    <button
      type="button"
      onClick={async () => {
        try {
          await navigator.clipboard.writeText(text)
          setCopied(true)
          setTimeout(() => setCopied(false), 1800)
        } catch {
          // Clipboard blocked; the text is selectable.
        }
      }}
      className="inline-flex shrink-0 items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium text-zinc-600 hover:bg-zinc-200/70 hover:text-zinc-900 dark:text-zinc-400 dark:hover:bg-zinc-700 dark:hover:text-white"
      aria-label={label}
    >
      {copied ? <Check className="size-3.5" aria-hidden /> : <Copy className="size-3.5" aria-hidden />}
      {copied ? 'Copied' : 'Copy'}
    </button>
  )
}

interface Props {
  monitor: Monitor
  onTestPing: () => Promise<void>
}

/** How to wire a job up to a heartbeat: the ping URL, ready-to-paste snippets, and a test ping. */
export function HeartbeatSetup({ monitor, onTestPing }: Props) {
  const [tab, setTab] = useState<Snippet>('curl')
  const [pinging, setPinging] = useState(false)
  const [pinged, setPinged] = useState(false)
  const url = monitor.pingUrl ?? ''
  const code = snippets(url, monitor.intervalSeconds)[tab]

  async function test() {
    setPinging(true)
    try {
      await onTestPing()
      setPinged(true)
    } finally {
      setPinging(false)
    }
  }

  return (
    <Card className="p-4 sm:p-5">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h2 className="text-base font-semibold">Ping URL</h2>
          <p className="mt-0.5 text-sm text-zinc-500 dark:text-zinc-400">
            Call this at the end of your job, at least every {formatDuration(monitor.intervalSeconds)}. GET or POST both work.
          </p>
        </div>
        <Button type="button" variant="secondary" size="sm" onClick={test} loading={pinging}>
          {!pinging && <Send className="size-3.5" aria-hidden />}
          Send test ping
        </Button>
      </div>

      <div className="mt-4 flex items-center gap-2 rounded-lg border border-zinc-200 bg-zinc-50 py-1.5 pr-1.5 pl-3 dark:border-zinc-800 dark:bg-zinc-950">
        <code className="min-w-0 flex-1 truncate font-mono text-sm" title={url}>
          {url}
        </code>
        <CopyButton text={url} label="Copy ping URL" />
      </div>
      <p className="mt-1.5 text-xs text-zinc-500 dark:text-zinc-400">
        Keep it private: anyone with this URL can mark the job as healthy.
        {pinged && <span className="ml-1 font-medium text-emerald-700 dark:text-emerald-400">Test ping received.</span>}
      </p>

      <div className="mt-4 overflow-hidden rounded-lg border border-zinc-200 dark:border-zinc-800">
        <div className="flex items-center justify-between border-b border-zinc-200 bg-zinc-50 px-2 dark:border-zinc-800 dark:bg-zinc-900" role="tablist" aria-label="Examples">
          <div className="flex">
            {(['curl', 'cron', 'bash'] as const).map((t) => (
              <button
                key={t}
                type="button"
                role="tab"
                aria-selected={tab === t}
                onClick={() => setTab(t)}
                className={cn(
                  '-mb-px border-b-2 px-3 py-2 text-xs font-medium',
                  tab === t
                    ? 'border-emerald-600 text-zinc-900 dark:text-white'
                    : 'border-transparent text-zinc-500 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-white',
                )}
              >
                {t === 'curl' ? 'curl' : t === 'cron' ? 'crontab' : 'Shell script'}
              </button>
            ))}
          </div>
          <CopyButton text={code} label={`Copy ${tab} example`} />
        </div>
        <pre className="overflow-x-auto bg-zinc-950 p-3 text-xs leading-relaxed text-zinc-100">
          <code>{code}</code>
        </pre>
      </div>
    </Card>
  )
}

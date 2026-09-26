import { Fragment, useEffect, useRef, useState, type FormEvent, type KeyboardEvent, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { Link } from 'react-router-dom'
import { ArrowLeft, ArrowUp, Check, Globe, HeartPulse, SlidersHorizontal, Sparkles, X } from 'lucide-react'
import { askAi, getAiAccess, getAiQuota, saveAiAccess, type AiAccess, type AiLink, type AiQuota } from '../../api/ai'
import { ApiError } from '../../api/errors'
import { listMonitors } from '../../api/monitors'
import type { MonitorWithStats } from '../../types/monitor'
import { cn } from '../../lib/format'
import { Spinner } from '../ui/Spinner'
import { PulseMascot } from './PulseMascot'
import { Switch } from '../ui/Switch'

interface Message {
  role: 'user' | 'assistant' | 'error'
  text: string
  links?: AiLink[]
  upgrade?: boolean
}

type View = 'loading' | 'setup' | 'review' | 'chat'

const GENERIC_SUGGESTIONS = ['Which monitor is slowest this week?', 'What does 503 mean?', "What's down right now?"]

/** Renders the answer format: blank-line paragraphs, "- " bullets, **bold**. Never uses innerHTML. */
function RichText({ text }: { text: string }) {
  const inline = (s: string): ReactNode[] =>
    s.split(/(\*\*[^*]+\*\*)/g).map((part, i) =>
      part.startsWith('**') && part.endsWith('**') ? <strong key={i}>{part.slice(2, -2)}</strong> : <Fragment key={i}>{part}</Fragment>,
    )
  return (
    <div className="space-y-2">
      {text.split('\n\n').map((block, i) => {
        const lines = block.split('\n')
        if (lines.every((l) => l.startsWith('- '))) {
          return (
            <ul key={i} className="list-disc space-y-0.5 pl-5">
              {lines.map((l, j) => (
                <li key={j}>{inline(l.slice(2))}</li>
              ))}
            </ul>
          )
        }
        return <p key={i}>{inline(block)}</p>
      })}
    </div>
  )
}

interface Props {
  open: boolean
  onClose: () => void
}

export function AskAiPanel({ open, onClose }: Props) {
  const [view, setView] = useState<View>('loading')
  const [access, setAccess] = useState<AiAccess | null>(null)
  const [draft, setDraft] = useState<AiAccess>({ enabled: false, allMonitors: true, monitorIds: [] })
  const [monitors, setMonitors] = useState<MonitorWithStats[] | null>(null)
  const [saving, setSaving] = useState(false)
  const [setupError, setSetupError] = useState<string | null>(null)

  const [messages, setMessages] = useState<Message[]>([])
  const [input, setInput] = useState('')
  const [thinking, setThinking] = useState(false)
  const [quota, setQuota] = useState<AiQuota | null>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const listRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    let cancelled = false
    Promise.all([getAiAccess(), listMonitors(), getAiQuota()]).then(([a, m, q]) => {
      if (cancelled) return
      setAccess(a)
      setDraft(a.enabled ? a : { enabled: false, allMonitors: true, monitorIds: m.map((x) => x.id) })
      setMonitors([...m].sort((x, y) => x.name.localeCompare(y.name)))
      setQuota(q)
      setView(a.enabled ? 'chat' : 'setup')
    })
    const onKey = (e: globalThis.KeyboardEvent) => e.key === 'Escape' && onClose()
    document.addEventListener('keydown', onKey)
    return () => {
      cancelled = true
      document.removeEventListener('keydown', onKey)
    }
  }, [open, onClose])

  useEffect(() => {
    if (view === 'chat') inputRef.current?.focus()
  }, [view])

  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight, behavior: 'smooth' })
  }, [messages, thinking])

  async function enable() {
    setSaving(true)
    setSetupError(null)
    try {
      const saved = await saveAiAccess({ ...draft, enabled: true })
      setAccess(saved)
      setDraft(saved)
      setView('chat')
    } catch (e) {
      setSetupError(e instanceof Error ? e.message : 'Could not save')
    } finally {
      setSaving(false)
    }
  }

  async function disable() {
    setSaving(true)
    try {
      const saved = await saveAiAccess({ ...draft, enabled: false })
      setAccess(saved)
      setMessages([])
      setView('setup')
    } finally {
      setSaving(false)
    }
  }

  async function ask(question: string) {
    const q = question.trim()
    if (!q || thinking) return
    setInput('')
    setMessages((m) => [...m, { role: 'user', text: q }])
    setThinking(true)
    try {
      const res = await askAi(q)
      setQuota(res.quota)
      setMessages((m) => [...m, { role: 'assistant', text: res.answer, links: res.links }])
    } catch (err) {
      const rateLimited = err instanceof ApiError && err.status === 429
      setMessages((m) => [...m, { role: 'error', text: err instanceof Error ? err.message : 'Something went wrong. Try again.', upgrade: rateLimited }])
    } finally {
      setThinking(false)
      inputRef.current?.focus()
    }
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault()
    ask(input)
  }

  function onKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      ask(input)
    }
  }

  if (!open) return null

  const selectedCount = draft.allMonitors ? (monitors?.length ?? 0) : draft.monitorIds.length
  const canContinue = draft.allMonitors || draft.monitorIds.length > 0
  const remaining = quota && quota.limit !== null ? Math.max(0, quota.limit - quota.used) : null
  // Lead with a monitor Ask AI can actually see, preferring one that's having problems.
  const inScope = (monitors ?? []).filter((m) => access?.allMonitors || access?.monitorIds.includes(m.id))
  const example = inScope.find((m) => m.isActive && m.lastCheckedAt && m.state !== 'UP') ?? inScope[0]
  const suggestions = example ? [`Why did ${example.name} go down?`, ...GENERIC_SUGGESTIONS] : GENERIC_SUGGESTIONS
  const scopeLabel = access?.allMonitors ? 'All monitors' : `${access?.monitorIds.length ?? 0} monitor${access?.monitorIds.length === 1 ? '' : 's'}`

  return createPortal(
    // Docked, not modal: the page stays visible and usable beside it, like Cloudflare's assistant.
    <aside
      className="fixed inset-y-0 right-0 z-40 flex w-full flex-col border-l border-zinc-200 bg-zinc-50 shadow-[-12px_0_32px_-16px_rgb(0_0_0/0.18)] sm:w-[440px] dark:border-zinc-800 dark:bg-zinc-950"
      aria-label="Ask AI"
    >
      <header className="flex h-16 shrink-0 items-center gap-2 border-b border-zinc-200 bg-white px-4 dark:border-zinc-800 dark:bg-zinc-900">
        {view === 'chat' && (
          <>
            <Sparkles className="size-4.5 text-emerald-600 dark:text-emerald-400" aria-hidden />
            <h2 className="text-sm font-semibold">Ask AI</h2>
            <span className="rounded-full bg-zinc-100 px-1.5 py-0.5 text-[10px] font-medium text-zinc-500 uppercase dark:bg-zinc-800 dark:text-zinc-400">
              Preview
            </span>
          </>
        )}
        <div className="ml-auto flex items-center gap-1">
          {view === 'chat' && (
            <button
              onClick={() => setView('setup')}
              className="rounded-md p-1.5 text-zinc-500 hover:bg-zinc-100 hover:text-zinc-900 dark:hover:bg-zinc-800 dark:hover:text-white"
              aria-label="Manage Ask AI access"
              title="Manage access"
            >
              <SlidersHorizontal className="size-4.5" />
            </button>
          )}
          <button
            onClick={onClose}
            className="rounded-md p-1.5 text-zinc-500 hover:bg-zinc-100 hover:text-zinc-900 dark:hover:bg-zinc-800 dark:hover:text-white"
            aria-label="Close Ask AI"
          >
            <X className="size-5" />
          </button>
        </div>
      </header>

      {/* Dotted canvas behind every view. */}
      <div
        className="relative flex min-h-0 flex-1 flex-col [--dot:rgb(0_0_0/0.09)] dark:[--dot:rgb(255_255_255/0.08)]"
        style={{ backgroundImage: 'radial-gradient(var(--dot) 1px, transparent 1px)', backgroundSize: '16px 16px' }}
      >
        {view === 'loading' && (
          <div className="flex flex-1 items-center justify-center text-zinc-400">
            <Spinner />
          </div>
        )}

        {(view === 'setup' || view === 'review') && (
          <div className="flex flex-1 flex-col items-center justify-center overflow-y-auto px-5 py-8">
            <PulseMascot thinking={thinking} />
            <div className="mt-6 w-full max-w-sm rounded-2xl border border-zinc-200 bg-white p-5 shadow-lg dark:border-zinc-800 dark:bg-zinc-900">
              {view === 'setup' ? (
                <>
                  <h3 className="text-center text-base font-semibold">{access?.enabled ? 'Ask AI access' : 'Enable Ask AI access'}</h3>
                  <p className="mt-1.5 text-center text-sm text-zinc-500 dark:text-zinc-400">
                    Ask AI reads the monitors you choose to answer questions about uptime, incidents and errors.
                  </p>

                  <div className="mt-5 flex items-center justify-between gap-4 rounded-xl border border-zinc-200 p-3 dark:border-zinc-800">
                    <div>
                      <p className="text-sm font-medium">Grant access to all monitors</p>
                      <p className="text-xs text-zinc-500 dark:text-zinc-400">Includes monitors you add in the future</p>
                    </div>
                    <Switch
                      checked={draft.allMonitors}
                      onChange={(v) => setDraft((d) => ({ ...d, allMonitors: v }))}
                      label="Grant access to all monitors"
                    />
                  </div>

                  <div className="mt-4 flex items-center justify-between text-xs text-zinc-500 dark:text-zinc-400">
                    <span>Select monitor(s)</span>
                    <span>{selectedCount} selected</span>
                  </div>
                  <ul className="mt-2 max-h-56 space-y-2 overflow-y-auto pr-0.5">
                    {monitors?.map((m) => {
                      const checked = draft.allMonitors || draft.monitorIds.includes(m.id)
                      const Icon = m.type === 'HEARTBEAT' ? HeartPulse : Globe
                      return (
                        <li key={m.id}>
                          <label
                            className={cn(
                              'flex items-center gap-3 rounded-xl border px-3 py-2.5 transition-colors',
                              draft.allMonitors ? 'cursor-default opacity-70' : 'cursor-pointer',
                              checked
                                ? 'border-emerald-500 bg-emerald-50/60 dark:border-emerald-500/70 dark:bg-emerald-500/10'
                                : 'border-zinc-200 hover:bg-zinc-50 dark:border-zinc-800 dark:hover:bg-zinc-800/50',
                            )}
                          >
                            <input
                              type="checkbox"
                              checked={checked}
                              disabled={draft.allMonitors}
                              onChange={(e) =>
                                setDraft((d) => ({
                                  ...d,
                                  monitorIds: e.target.checked ? [...d.monitorIds, m.id] : d.monitorIds.filter((x) => x !== m.id),
                                }))
                              }
                              className="size-4 shrink-0 accent-emerald-600"
                            />
                            <span className="min-w-0 flex-1">
                              <span className="block truncate text-sm font-medium">{m.name}</span>
                              <span className="flex items-center gap-1 text-xs text-zinc-500 dark:text-zinc-400">
                                <Icon className="size-3" aria-hidden />
                                {m.type === 'HEARTBEAT' ? 'Heartbeat' : 'Website or API'}
                              </span>
                            </span>
                          </label>
                        </li>
                      )
                    })}
                  </ul>
                  {!canContinue && <p className="mt-3 text-center text-xs text-zinc-500 dark:text-zinc-400">At least one monitor must be selected.</p>}

                  <button
                    onClick={() => setView('review')}
                    disabled={!canContinue}
                    className="mt-4 h-10 w-full rounded-lg bg-emerald-600 text-sm font-medium text-white hover:bg-emerald-700 disabled:opacity-50"
                  >
                    Review permissions
                  </button>
                  {access?.enabled && (
                    <div className="mt-3 flex justify-between text-xs">
                      <button onClick={() => setView('chat')} className="font-medium text-zinc-600 hover:underline dark:text-zinc-400">
                        Back to chat
                      </button>
                      <button onClick={disable} disabled={saving} className="font-medium text-red-600 hover:underline dark:text-red-400">
                        Turn off Ask AI
                      </button>
                    </div>
                  )}
                </>
              ) : (
                <>
                  <button
                    onClick={() => setView('setup')}
                    className="-mt-1 mb-2 inline-flex items-center gap-1 text-xs font-medium text-zinc-500 hover:text-zinc-900 dark:text-zinc-400 dark:hover:text-white"
                  >
                    <ArrowLeft className="size-3.5" aria-hidden /> Back
                  </button>
                  <h3 className="text-center text-base font-semibold">Review permissions</h3>
                  <p className="mt-1.5 text-center text-sm text-zinc-500 dark:text-zinc-400">
                    For {draft.allMonitors ? 'all monitors, including future ones' : `${draft.monitorIds.length} selected monitor${draft.monitorIds.length === 1 ? '' : 's'}`}.
                  </p>
                  <p className="mt-5 text-xs font-medium tracking-wide text-zinc-500 uppercase dark:text-zinc-400">Ask AI can read</p>
                  <ul className="mt-2 space-y-1.5 text-sm">
                    {['Monitor names and settings', 'Check and ping history, response times', 'Incidents and alert delivery history'].map((t) => (
                      <li key={t} className="flex items-start gap-2">
                        <Check className="mt-0.5 size-4 shrink-0 text-emerald-600 dark:text-emerald-400" aria-hidden />
                        {t}
                      </li>
                    ))}
                  </ul>
                  <p className="mt-4 text-xs font-medium tracking-wide text-zinc-500 uppercase dark:text-zinc-400">Ask AI can’t</p>
                  <ul className="mt-2 space-y-1.5 text-sm text-zinc-600 dark:text-zinc-400">
                    {['Change, pause or delete anything', 'See passwords, API keys, ping URLs or webhook URLs', 'Read monitors you didn’t select'].map((t) => (
                      <li key={t} className="flex items-start gap-2">
                        <X className="mt-0.5 size-4 shrink-0 text-zinc-400" aria-hidden />
                        {t}
                      </li>
                    ))}
                  </ul>
                  {setupError && <p className="mt-3 text-center text-sm text-red-600 dark:text-red-400">{setupError}</p>}
                  <button
                    onClick={enable}
                    disabled={saving}
                    className="mt-5 flex h-10 w-full items-center justify-center gap-2 rounded-lg bg-emerald-600 text-sm font-medium text-white hover:bg-emerald-700 disabled:opacity-60"
                  >
                    {saving && <Spinner className="size-4" />}
                    {access?.enabled ? 'Save access' : 'Enable Ask AI'}
                  </button>
                </>
              )}
            </div>
          </div>
        )}

        {view === 'chat' && (
          <>
            <div ref={listRef} className="flex-1 space-y-4 overflow-y-auto p-4" aria-live="polite">
              {messages.length === 0 && (
                <div className="flex flex-col items-center pt-6">
                  <PulseMascot thinking={thinking} />
                  <p className="mt-5 text-center text-base font-semibold">What do you want to know?</p>
                  <p className="mt-1 text-center text-sm text-zinc-500 dark:text-zinc-400">Answers use {scopeLabel.toLowerCase()} you gave access to.</p>
                  <div className="mt-5 flex w-full flex-col gap-2">
                    {suggestions.map((s) => (
                      <button
                        key={s}
                        onClick={() => ask(s)}
                        className="rounded-xl border border-zinc-200 bg-white px-3.5 py-2.5 text-left text-sm text-zinc-700 shadow-sm hover:border-emerald-300 hover:bg-emerald-50 dark:border-zinc-800 dark:bg-zinc-900 dark:text-zinc-300 dark:hover:border-emerald-500/40 dark:hover:bg-emerald-500/10"
                      >
                        {s}
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {messages.map((m, i) =>
                m.role === 'user' ? (
                  <div key={i} className="flex justify-end">
                    <p className="max-w-[85%] rounded-2xl rounded-br-md bg-emerald-600 px-3.5 py-2 text-sm whitespace-pre-wrap text-white shadow-sm">{m.text}</p>
                  </div>
                ) : (
                  <div
                    key={i}
                    className={cn(
                      'max-w-[92%] rounded-2xl rounded-bl-md border px-3.5 py-2.5 text-sm shadow-sm',
                      m.role === 'error'
                        ? 'border-red-200 bg-red-50 text-red-800 dark:border-red-500/30 dark:bg-red-500/10 dark:text-red-200'
                        : 'border-zinc-200 bg-white text-zinc-800 dark:border-zinc-800 dark:bg-zinc-900 dark:text-zinc-100',
                    )}
                  >
                    <RichText text={m.text} />
                    {m.upgrade && (
                      <Link to="/billing" onClick={onClose} className="mt-2 inline-block font-medium underline underline-offset-2">
                        Upgrade for more questions
                      </Link>
                    )}
                    {!!m.links?.length && (
                      <div className="mt-2.5 flex flex-wrap gap-2">
                        {m.links.map((l) => (
                          <Link
                            key={l.to}
                            to={l.to}
                            className="rounded-md border border-zinc-300 bg-white px-2 py-1 text-xs font-medium hover:bg-zinc-50 dark:border-zinc-600 dark:bg-zinc-900 dark:hover:bg-zinc-800"
                          >
                            {l.label}
                          </Link>
                        ))}
                      </div>
                    )}
                  </div>
                ),
              )}

              {thinking && (
                <div className="flex items-end gap-1" aria-label="Thinking">
                  {/* A half-size mascot, glancing around while the answer is on its way. */}
                  <div className="-mb-2 -ml-6 h-16 w-24 shrink-0">
                    <PulseMascot thinking className="origin-top-left scale-50" />
                  </div>
                  <div className="-ml-4 mb-3 flex items-center gap-1 rounded-2xl rounded-bl-md border border-zinc-200 bg-white px-3 py-2.5 dark:border-zinc-800 dark:bg-zinc-900">
                    {[0, 150, 300].map((d) => (
                      <span key={d} className="size-1.5 animate-bounce rounded-full bg-zinc-400" style={{ animationDelay: `${d}ms` }} />
                    ))}
                  </div>
                </div>
              )}
            </div>

            <form onSubmit={onSubmit} className="shrink-0 p-3">
              <div className="flex items-end gap-2 rounded-2xl border border-zinc-200 bg-white p-1.5 shadow-lg focus-within:border-emerald-600 dark:border-zinc-800 dark:bg-zinc-900">
                <textarea
                  ref={inputRef}
                  value={input}
                  onChange={(e) => setInput(e.target.value)}
                  onKeyDown={onKeyDown}
                  rows={1}
                  maxLength={500}
                  placeholder="Ask a question…"
                  aria-label="Your question"
                  className="max-h-32 min-h-9 flex-1 resize-none bg-transparent px-2 py-1.5 text-sm outline-none placeholder:text-zinc-400"
                />
                <button
                  type="submit"
                  disabled={!input.trim() || thinking || remaining === 0}
                  className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-emerald-600 text-white hover:bg-emerald-700 disabled:opacity-40"
                  aria-label="Send"
                >
                  <ArrowUp className="size-4" />
                </button>
              </div>
              <p className="mt-1.5 px-1 text-[11px] text-zinc-500 dark:text-zinc-400">
                {scopeLabel} · {remaining === null ? 'unlimited questions' : `${remaining} of ${quota?.limit} questions left today`}. Answers
                can be wrong — check the linked pages.
              </p>
            </form>
          </>
        )}
      </div>
    </aside>,
    document.body,
  )
}

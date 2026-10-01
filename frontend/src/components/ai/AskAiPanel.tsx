import { Fragment, useEffect, useRef, useState, type FormEvent, type KeyboardEvent, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { Link } from 'react-router-dom'
import {
  ArrowLeft,
  ArrowUp,
  BookOpen,
  Check,
  Globe,
  HeartPulse,
  MessagesSquare,
  Pencil,
  RotateCcw,
  Search,
  SlidersHorizontal,
  Sparkles,
  Square,
  SquarePen,
  ThumbsDown,
  ThumbsUp,
  Trash2,
  X,
} from 'lucide-react'
import {
  createConversation,
  deleteConversation,
  getAiAccess,
  getAiQuota,
  getMessages,
  listConversations,
  rateMessage,
  renameConversation,
  saveAiAccess,
  streamMessage,
  type AiAccess,
  type AiConversation,
  type AiMessage,
  type AiQuota,
  type HelpSource,
  type MessageStatus,
} from '../../api/ai'
import { ApiError } from '../../api/errors'
import { listMonitors } from '../../api/monitors'
import type { MonitorWithStats } from '../../types/monitor'
import { mergeSources, splitCitations } from '../../lib/citations'
import type { AskAiPageContext } from '../../lib/events'
import { cn, formatDateTime } from '../../lib/format'
import { Button } from '../ui/Button'
import { Modal } from '../ui/Modal'
import { Spinner } from '../ui/Spinner'
import { PulseMascot } from './PulseMascot'
import { Switch } from '../ui/Switch'

/** One bubble in the chat. `key` is local; `id` is the saved message's, once the server has it. */
interface ChatItem {
  key: string
  role: 'user' | 'assistant' | 'error'
  text: string
  id?: number
  status?: MessageStatus
  rating?: 1 | -1 | null
  /** The answer is still arriving. */
  streaming?: boolean
  /** 429: offer the upgrade link. */
  upgrade?: boolean
  /** The question to send again from a "Try again" button (only when it wasn't counted). */
  retry?: string
  /** What the model looked up for this answer, e.g. "Checked uptime for Health, 2026-09-01". */
  lookups?: string[]
  /** Help-doc sections the answer can cite; "[1]" in the text is the first. */
  sources?: HelpSource[]
}

type View = 'loading' | 'setup' | 'review' | 'chat' | 'history'

let nextKey = 0
const key = () => `m${nextKey++}`

function toItem(m: AiMessage): ChatItem {
  return {
    key: key(),
    id: m.id,
    role: m.role === 'USER' ? 'user' : 'assistant',
    text: m.content,
    status: m.status,
    rating: m.rating,
    lookups: m.lookups ?? [],
    sources: m.sources ?? [],
  }
}

const GENERIC_SUGGESTIONS = ['Which monitor is slowest this week?', 'What does 503 mean?', "What's down right now?"]
const INCIDENT_SUGGESTIONS = ['Why did this happen?', 'Has this happened before?', 'How long was it down, and who was alerted?']
const monitorSuggestions = (name: string) => [`How has ${name} been this week?`, 'Why did it fail most recently?', "What's its uptime over the last 30 days?"]

/** The lines under an answer saying what Ask AI looked up, so you can see what it's based on. */
function Lookups({ items }: { items?: string[] }) {
  if (!items?.length) return null
  return (
    <ul className="space-y-0.5 text-xs text-zinc-500 dark:text-zinc-400" aria-label="What Ask AI looked up">
      {items.map((label, i) => (
        <li key={i} className="flex items-start gap-1.5">
          <Search className="mt-0.5 size-3 shrink-0" aria-hidden />
          {label}
        </li>
      ))}
    </ul>
  )
}

/** Only links into the docs: a source is never a link the model could have made up. */
const isDocsUrl = (url: string) => url.startsWith('/docs/')

/** The numbered docs sections under an answer, linking to the section on the docs page. */
function Sources({ items }: { items?: HelpSource[] }) {
  if (!items?.length) return null
  return (
    <div className="mt-2.5 border-t border-zinc-100 pt-2 dark:border-zinc-800">
      <p className="flex items-center gap-1.5 text-xs font-medium text-zinc-500 dark:text-zinc-400">
        <BookOpen className="size-3.5" aria-hidden />
        Sources
      </p>
      <ol className="mt-1 space-y-0.5 text-xs" aria-label="Sources">
        {items.map((s, i) => (
          <li key={`${s.url} ${s.title}`} className="flex gap-1.5">
            <span className="shrink-0 text-zinc-400 tabular-nums">[{i + 1}]</span>
            {isDocsUrl(s.url) ? (
              <Link to={s.url} className="font-medium text-emerald-700 hover:underline dark:text-emerald-400">
                {s.title}
              </Link>
            ) : (
              <span>{s.title}</span>
            )}
          </li>
        ))}
      </ol>
    </div>
  )
}

/**
 * Text with its citations linked: "[1, 3]" becomes two small links to sources 1 and 3. A number
 * with no matching source stays as written, so a slip by the model never links somewhere wrong.
 */
function CitedText({ text, sources }: { text: string; sources: HelpSource[] }) {
  return (
    <>
      {splitCitations(text).map((part, i) => {
        if (typeof part === 'string') return <Fragment key={i}>{part}</Fragment>
        const source = (n: number) => {
          const s = sources[n - 1]
          return s && isDocsUrl(s.url) ? s : undefined
        }
        if (!part.numbers.some(source)) return <Fragment key={i}>{part.raw}</Fragment>
        return (
          <sup key={i} className="ml-0.5 inline-flex gap-0.5 align-super text-[0.7em] leading-none">
            {part.numbers.map((n, j) => {
              const s = source(n)
              return s ? (
                <Link
                  key={j}
                  to={s.url}
                  aria-label={`Source ${n}: ${s.title}`}
                  title={s.title}
                  className="rounded bg-emerald-50 px-1 py-0.5 font-semibold text-emerald-700 hover:bg-emerald-100 dark:bg-emerald-500/10 dark:text-emerald-400 dark:hover:bg-emerald-500/20"
                >
                  {n}
                </Link>
              ) : (
                <span key={j} className="px-0.5 text-zinc-500">
                  {n}
                </span>
              )
            })}
          </sup>
        )
      })}
    </>
  )
}

const BULLET = /^\s*[-*•]\s+/

/**
 * Renders the answer format: blank-line paragraphs, bullet lines ("- ", "* " or "• "), **bold**.
 * A paragraph can mix text and bullets ("Two incidents:" then the list), so lines are grouped:
 * each run of bullet lines becomes a list, each run of other lines a paragraph that keeps its
 * line breaks. Never uses innerHTML.
 */
function RichText({ text, sources = [] }: { text: string; sources?: HelpSource[] }) {
  const inline = (s: string): ReactNode[] =>
    s.split(/(\*\*[^*]+\*\*)/g).map((part, i) =>
      part.startsWith('**') && part.endsWith('**') ? (
        <strong key={i}>{part.slice(2, -2)}</strong>
      ) : (
        <CitedText key={i} text={part} sources={sources} />
      ),
    )
  const groups: { bullet: boolean; lines: string[] }[] = []
  for (const block of text.split(/\n\s*\n/)) {
    let previous: { bullet: boolean; lines: string[] } | null = null
    for (const line of block.split('\n')) {
      if (!line.trim()) continue
      const bullet = BULLET.test(line)
      if (previous && previous.bullet === bullet) {
        previous.lines.push(line)
      } else {
        previous = { bullet, lines: [line] }
        groups.push(previous)
      }
    }
  }
  return (
    <div className="space-y-2">
      {groups.map((g, i) =>
        g.bullet ? (
          <ul key={i} className="list-disc space-y-0.5 pl-5">
            {g.lines.map((l, j) => (
              <li key={j}>{inline(l.replace(BULLET, ''))}</li>
            ))}
          </ul>
        ) : (
          <p key={i} className="whitespace-pre-line">
            {inline(g.lines.join('\n'))}
          </p>
        ),
      )}
    </div>
  )
}

interface Props {
  open: boolean
  onClose: () => void
  /** "Ask AI about this" from a monitor or incident page; a new `key` starts a new chat about it. */
  page?: (AskAiPageContext & { key: number }) | null
}

export function AskAiPanel({ open, onClose, page = null }: Props) {
  const [view, setView] = useState<View>('loading')
  const [access, setAccess] = useState<AiAccess | null>(null)
  const [draft, setDraft] = useState<AiAccess>({ enabled: false, allMonitors: true, monitorIds: [] })
  const [monitors, setMonitors] = useState<MonitorWithStats[] | null>(null)
  const [saving, setSaving] = useState(false)
  const [setupError, setSetupError] = useState<string | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  const [conversations, setConversations] = useState<AiConversation[]>([])
  /** null: a new chat, created on the server when its first question is sent. */
  const [chatId, setChatId] = useState<number | null>(null)
  /** The page the current chat is about, if any; sent when the chat is created. */
  const [about, setAbout] = useState<AskAiPageContext | null>(null)
  const [items, setItems] = useState<ChatItem[]>([])
  const [loadingChat, setLoadingChat] = useState(false)
  const [editing, setEditing] = useState<{ id: number; title: string } | null>(null)
  /** The chat waiting for delete confirmation; null keeps the dialog closed. */
  const [toDelete, setToDelete] = useState<AiConversation | null>(null)
  const [deleteBusy, setDeleteBusy] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)
  const [input, setInput] = useState('')
  const [streaming, setStreaming] = useState(false)
  const [quota, setQuota] = useState<AiQuota | null>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const listRef = useRef<HTMLDivElement>(null)
  /** Aborting it is Stop; closing the panel mid-answer aborts too. */
  const abortRef = useRef<AbortController | null>(null)
  // A new "Ask AI about this" click: start a fresh chat about that page. Done while rendering
  // (React's pattern for reacting to a changed prop), so the old chat never flashes first.
  const [seenPageKey, setSeenPageKey] = useState<number | null>(null)
  if (page && page.key !== seenPageKey) {
    setSeenPageKey(page.key)
    setAbout({ monitorId: page.monitorId, incidentId: page.incidentId, label: page.label })
    setChatId(null)
    setItems([])
    setView((v) => (v === 'history' ? 'chat' : v))
  }

  useEffect(() => {
    if (!open) return
    let cancelled = false
    Promise.all([getAiAccess(), listMonitors(), getAiQuota(), listConversations()])
      .then(([a, m, q, c]) => {
        if (cancelled) return
        setLoadError(null)
        setAccess(a)
        setDraft(a.enabled ? a : { enabled: false, allMonitors: true, monitorIds: m.map((x) => x.id) })
        setMonitors([...m].sort((x, y) => x.name.localeCompare(y.name)))
        setQuota(q)
        setConversations(c)
        setView(a.enabled ? 'chat' : 'setup')
      })
      .catch((e) => !cancelled && setLoadError(e instanceof Error ? e.message : 'Could not load Ask AI'))
    // Esc closes the panel, unless a dialog (delete confirmation) is open: Esc closes that instead.
    const onKey = (e: globalThis.KeyboardEvent) => e.key === 'Escape' && !document.querySelector('dialog[open]') && onClose()
    document.addEventListener('keydown', onKey)
    return () => {
      cancelled = true
      document.removeEventListener('keydown', onKey)
      abortRef.current?.abort() // closing the panel stops an answer in progress
    }
  }, [open, onClose])

  useEffect(() => {
    if (view === 'chat') inputRef.current?.focus()
  }, [view])

  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight, behavior: 'smooth' })
  }, [items])

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
      setChatId(null)
      setItems([])
      setView('setup')
    } finally {
      setSaving(false)
    }
  }

  const update = (itemKey: string, change: (item: ChatItem) => ChatItem) =>
    setItems((list) => list.map((item) => (item.key === itemKey ? change(item) : item)))
  const remove = (itemKey: string) => setItems((list) => list.filter((item) => item.key !== itemKey))
  const refreshConversations = () => listConversations().then(setConversations).catch(() => {})

  async function ask(question: string) {
    const q = question.trim()
    if (!q || streaming) return
    setInput('')
    const answerKey = key()
    setItems((list) => [
      ...list.filter((item) => item.role !== 'error'),
      { key: key(), role: 'user', text: q },
      { key: answerKey, role: 'assistant', text: '', streaming: true },
    ])
    setStreaming(true)
    const controller = new AbortController()
    abortRef.current = controller
    try {
      let id = chatId
      if (id === null) {
        // An incident implies its monitor; the server takes one or the other, never both.
        id = (await createConversation(about ? (about.incidentId ? { incidentId: about.incidentId } : { monitorId: about.monitorId }) : undefined)).id
        setChatId(id)
      }
      const result = await streamMessage(id, q, {
        signal: controller.signal,
        onDelta: (text) => update(answerKey, (a) => ({ ...a, text: a.text + text })),
        onTool: (label, sources) =>
          update(answerKey, (a) => ({ ...a, lookups: [...(a.lookups ?? []), label], sources: mergeSources(a.sources ?? [], sources) })),
      })
      if (result.kind === 'done') {
        update(answerKey, (a) => ({ ...a, id: result.done.answerId, status: 'COMPLETE', streaming: false }))
        setQuota(result.done.quota)
      } else if (result.kind === 'stopped') {
        update(answerKey, (a) => ({ ...a, status: 'PARTIAL', streaming: false }))
        getAiQuota().then(setQuota).catch(() => {})
      } else {
        const { message, counted, answerId } = result.error
        if (answerId !== null) update(answerKey, (a) => ({ ...a, id: answerId, status: 'PARTIAL', streaming: false }))
        else remove(answerKey)
        setItems((list) => [...list, { key: key(), role: 'error', text: message, retry: counted ? undefined : q }])
      }
      refreshConversations()
    } catch (err) {
      // Refused before any answer started: 403, 409, 429, or the server couldn't be reached.
      remove(answerKey)
      const status = err instanceof ApiError ? err.status : 0
      setItems((list) => [
        ...list,
        {
          key: key(),
          role: 'error',
          text: err instanceof Error ? err.message : 'Something went wrong. Try again.',
          upgrade: status === 429,
          retry: status === 0 || status >= 500 ? q : undefined,
        },
      ])
    } finally {
      abortRef.current = null
      setStreaming(false)
      inputRef.current?.focus()
    }
  }

  function stop() {
    abortRef.current?.abort()
  }

  function newChat() {
    if (streaming) return
    setChatId(null)
    setAbout(null)
    setItems([])
    setView('chat')
  }

  /** What a saved chat is about, in words, from the monitors this panel already loaded. */
  function aboutOf(c: AiConversation): AskAiPageContext | null {
    const name = monitors?.find((m) => m.id === c.contextMonitorId)?.name ?? 'a monitor'
    // != null: also covers a field the server left out.
    if (c.contextIncidentId != null) return { incidentId: c.contextIncidentId, label: `an incident on ${name}` }
    if (c.contextMonitorId != null) return { monitorId: c.contextMonitorId, label: name }
    return null
  }

  async function openConversation(c: AiConversation) {
    if (streaming) return
    setChatId(c.id)
    setAbout(aboutOf(c))
    setItems([])
    setView('chat')
    setLoadingChat(true)
    try {
      setItems((await getMessages(c.id)).map(toItem))
    } catch (err) {
      setItems([{ key: key(), role: 'error', text: err instanceof Error ? err.message : 'Could not open this chat.' }])
    } finally {
      setLoadingChat(false)
    }
  }

  async function saveTitle() {
    if (!editing) return
    const { id, title } = editing
    setEditing(null)
    if (!title.trim()) return
    try {
      const renamed = await renameConversation(id, title.trim())
      setConversations((list) => list.map((c) => (c.id === id ? renamed : c)))
    } catch {
      // Keeps the old name; the list shows what the server has.
    }
  }

  async function confirmDelete() {
    if (!toDelete) return
    const c = toDelete
    setDeleteBusy(true)
    setDeleteError(null)
    try {
      await deleteConversation(c.id)
      setConversations((list) => list.filter((x) => x.id !== c.id))
      if (chatId === c.id) {
        setChatId(null)
        setItems([])
      }
      setToDelete(null)
    } catch (e) {
      setDeleteError(e instanceof Error ? e.message : 'Could not delete this chat')
    } finally {
      setDeleteBusy(false)
    }
  }

  function closeDeleteDialog() {
    if (deleteBusy) return
    setDeleteError(null)
    setToDelete(null)
  }

  async function rate(item: ChatItem, rating: 1 | -1) {
    if (!item.id) return
    const previous = item.rating ?? null
    update(item.key, (a) => ({ ...a, rating }))
    try {
      await rateMessage(item.id, rating)
    } catch {
      update(item.key, (a) => ({ ...a, rating: previous }))
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
  const suggestions = about?.incidentId
    ? INCIDENT_SUGGESTIONS
    : about?.monitorId
      ? monitorSuggestions(about.label)
      : example
        ? [`Why did ${example.name} go down?`, ...GENERIC_SUGGESTIONS]
        : GENERIC_SUGGESTIONS
  const scopeLabel = access?.allMonitors ? 'All monitors' : `${access?.monitorIds.length ?? 0} monitor${access?.monitorIds.length === 1 ? '' : 's'}`

  return createPortal(
    // Docked, not modal: the page stays visible and usable beside it, like Cloudflare's assistant.
    <aside
      className="fixed inset-y-0 right-0 z-40 flex w-full flex-col border-l border-zinc-200 bg-zinc-50 shadow-[-12px_0_32px_-16px_rgb(0_0_0/0.18)] sm:w-[440px] dark:border-zinc-800 dark:bg-zinc-950"
      aria-label="Ask AI"
    >
      <header className="flex h-16 shrink-0 items-center gap-2 border-b border-zinc-200 bg-white px-4 dark:border-zinc-800 dark:bg-zinc-900">
        {(view === 'chat' || view === 'history') && (
          <>
            <button
              onClick={() => setView(view === 'history' ? 'chat' : 'history')}
              disabled={streaming}
              className={cn(
                '-ml-1.5 rounded-md p-1.5 text-zinc-500 hover:bg-zinc-100 hover:text-zinc-900 disabled:opacity-40 dark:hover:bg-zinc-800 dark:hover:text-white',
                view === 'history' && 'bg-zinc-100 text-zinc-900 dark:bg-zinc-800 dark:text-white',
              )}
              aria-label="Your chats"
              aria-pressed={view === 'history'}
              title="Your chats"
            >
              <MessagesSquare className="size-4.5" />
            </button>
            <Sparkles className="size-4.5 text-emerald-600 dark:text-emerald-400" aria-hidden />
            <h2 className="text-sm font-semibold">Ask AI</h2>
            <span className="rounded-full bg-zinc-100 px-1.5 py-0.5 text-[10px] font-medium text-zinc-500 uppercase dark:bg-zinc-800 dark:text-zinc-400">
              Preview
            </span>
          </>
        )}
        <div className="ml-auto flex items-center gap-1">
          {(view === 'chat' || view === 'history') && (
            <button
              onClick={newChat}
              disabled={streaming}
              className="rounded-md p-1.5 text-zinc-500 hover:bg-zinc-100 hover:text-zinc-900 disabled:opacity-40 dark:hover:bg-zinc-800 dark:hover:text-white"
              aria-label="New chat"
              title="New chat"
            >
              <SquarePen className="size-4.5" />
            </button>
          )}
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
          <div className="flex flex-1 items-center justify-center px-6 text-center text-zinc-400">
            {loadError ? <p className="text-sm text-red-600 dark:text-red-400">{loadError}</p> : <Spinner />}
          </div>
        )}

        {(view === 'setup' || view === 'review') && (
          <div className="flex flex-1 flex-col items-center justify-center overflow-y-auto px-5 py-8">
            <PulseMascot thinking={streaming} />
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

        {view === 'history' && (
          <div className="flex-1 overflow-y-auto p-3">
            {conversations.length === 0 ? (
              <p className="pt-10 text-center text-sm text-zinc-500 dark:text-zinc-400">No chats yet. Ask a question to start one.</p>
            ) : (
              <ul className="space-y-1.5">
                {conversations.map((c) => (
                  <li
                    key={c.id}
                    className={cn(
                      'group flex items-center gap-1 rounded-xl border bg-white px-3 py-2 shadow-sm dark:bg-zinc-900',
                      c.id === chatId ? 'border-emerald-500 dark:border-emerald-500/70' : 'border-zinc-200 dark:border-zinc-800',
                    )}
                  >
                    {editing?.id === c.id ? (
                      <input
                        autoFocus
                        value={editing.title}
                        maxLength={100}
                        onChange={(e) => setEditing({ id: c.id, title: e.target.value })}
                        onBlur={saveTitle}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') saveTitle()
                          if (e.key === 'Escape') setEditing(null)
                        }}
                        aria-label="Chat name"
                        className="min-w-0 flex-1 rounded-md border border-zinc-300 bg-transparent px-2 py-1 text-sm outline-none focus:border-emerald-600 dark:border-zinc-700"
                      />
                    ) : (
                      <button onClick={() => openConversation(c)} className="min-w-0 flex-1 text-left">
                        <span className="block truncate text-sm font-medium">{c.title}</span>
                        <span className="block truncate text-xs text-zinc-500 dark:text-zinc-400">
                          {aboutOf(c) && <>About {aboutOf(c)!.label} · </>}
                          {formatDateTime(c.updatedAt)}
                        </span>
                      </button>
                    )}
                    <button
                      onClick={() => setEditing({ id: c.id, title: c.title })}
                      className="rounded-md p-1.5 text-zinc-400 hover:bg-zinc-100 hover:text-zinc-900 dark:hover:bg-zinc-800 dark:hover:text-white"
                      aria-label={`Rename ${c.title}`}
                    >
                      <Pencil className="size-3.5" />
                    </button>
                    <button
                      onClick={() => setToDelete(c)}
                      className="rounded-md p-1.5 text-zinc-400 hover:bg-red-50 hover:text-red-600 dark:hover:bg-red-500/10 dark:hover:text-red-400"
                      aria-label={`Delete ${c.title}`}
                    >
                      <Trash2 className="size-3.5" />
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}

        {view === 'chat' && (
          <>
            <div ref={listRef} className="flex-1 space-y-4 overflow-y-auto p-4" aria-live="polite">
              {about && (
                <p className="mx-auto flex w-fit max-w-full items-center gap-1.5 truncate rounded-full border border-emerald-200 bg-emerald-50 px-3 py-1 text-xs font-medium text-emerald-800 dark:border-emerald-500/30 dark:bg-emerald-500/10 dark:text-emerald-300">
                  <Sparkles className="size-3.5 shrink-0" aria-hidden />
                  About {about.label}
                </p>
              )}
              {loadingChat && (
                <div className="flex justify-center pt-10 text-zinc-400">
                  <Spinner />
                </div>
              )}
              {!loadingChat && items.length === 0 && (
                <div className="flex flex-col items-center pt-6">
                  <PulseMascot thinking={streaming} />
                  <p className="mt-5 text-center text-base font-semibold">What do you want to know?</p>
                  <p className="mt-1 text-center text-sm text-zinc-500 dark:text-zinc-400">Answers use {scopeLabel.toLowerCase()} you gave access to.</p>
                  <div className="mt-5 flex w-full flex-col gap-2">
                    {suggestions.map((q) => (
                      <button
                        key={q}
                        onClick={() => ask(q)}
                        className="rounded-xl border border-zinc-200 bg-white px-3.5 py-2.5 text-left text-sm text-zinc-700 shadow-sm hover:border-emerald-300 hover:bg-emerald-50 dark:border-zinc-800 dark:bg-zinc-900 dark:text-zinc-300 dark:hover:border-emerald-500/40 dark:hover:bg-emerald-500/10"
                      >
                        {q}
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {items.map((m) => {
                if (m.role === 'user') {
                  return (
                    <div key={m.key} className="flex justify-end">
                      <p className="max-w-[85%] rounded-2xl rounded-br-md bg-emerald-600 px-3.5 py-2 text-sm whitespace-pre-wrap text-white shadow-sm">{m.text}</p>
                    </div>
                  )
                }
                if (m.role === 'assistant' && m.streaming && !m.text) {
                  return (
                    <div key={m.key}>
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
                      {/* Lookups happen before any text, so they show while it's still thinking. */}
                      <Lookups items={m.lookups} />
                    </div>
                  )
                }
                if (m.role === 'error') {
                  return (
                    <div
                      key={m.key}
                      className="max-w-[92%] rounded-2xl rounded-bl-md border border-red-200 bg-red-50 px-3.5 py-2.5 text-sm text-red-800 shadow-sm dark:border-red-500/30 dark:bg-red-500/10 dark:text-red-200"
                    >
                      <p>{m.text}</p>
                      {m.upgrade && (
                        <Link to="/billing" onClick={onClose} className="mt-2 inline-block font-medium underline underline-offset-2">
                          Upgrade for more questions
                        </Link>
                      )}
                      {m.retry && (
                        <button
                          onClick={() => ask(m.retry!)}
                          disabled={streaming}
                          className="mt-2 inline-flex items-center gap-1.5 font-medium underline underline-offset-2 disabled:opacity-50"
                        >
                          <RotateCcw className="size-3.5" aria-hidden />
                          Try again
                        </button>
                      )}
                    </div>
                  )
                }
                const failed = m.status === 'FAILED'
                const stopped = m.status === 'PARTIAL'
                return (
                  <div
                    key={m.key}
                    className="max-w-[92%] rounded-2xl rounded-bl-md border border-zinc-200 bg-white px-3.5 py-2.5 text-sm text-zinc-800 shadow-sm dark:border-zinc-800 dark:bg-zinc-900 dark:text-zinc-100"
                  >
                    {!!m.lookups?.length && (
                      <div className="mb-2 border-b border-zinc-100 pb-2 dark:border-zinc-800">
                        <Lookups items={m.lookups} />
                      </div>
                    )}
                    {failed ? (
                      <p className="text-zinc-500 italic dark:text-zinc-400">No answer: Ask AI couldn't reply to this one.</p>
                    ) : m.text ? (
                      <RichText text={m.text} sources={m.sources} />
                    ) : (
                      <p className="text-zinc-500 italic dark:text-zinc-400">Stopped before answering.</p>
                    )}
                    {stopped && m.text && <p className="mt-1.5 text-xs text-zinc-500 dark:text-zinc-400">Stopped</p>}
                    {!failed && <Sources items={m.sources} />}
                    {!m.streaming && !failed && m.id !== undefined && m.text && (
                      <div className="mt-2 flex gap-1">
                        {([1, -1] as const).map((r) => {
                          const Icon = r === 1 ? ThumbsUp : ThumbsDown
                          return (
                            <button
                              key={r}
                              onClick={() => rate(m, r)}
                              aria-label={r === 1 ? 'Good answer' : 'Bad answer'}
                              aria-pressed={m.rating === r}
                              className={cn(
                                'rounded-md p-1 hover:bg-zinc-100 dark:hover:bg-zinc-800',
                                m.rating === r ? 'text-emerald-600 dark:text-emerald-400' : 'text-zinc-400',
                              )}
                            >
                              <Icon className="size-3.5" fill={m.rating === r ? 'currentColor' : 'none'} />
                            </button>
                          )
                        })}
                      </div>
                    )}
                  </div>
                )
              })}
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
                  placeholder={chatId === null ? 'Ask a question…' : 'Ask a follow-up…'}
                  aria-label="Your question"
                  className="max-h-32 min-h-9 flex-1 resize-none bg-transparent px-2 py-1.5 text-sm outline-none placeholder:text-zinc-400"
                />
                {streaming ? (
                  <button
                    type="button"
                    onClick={stop}
                    className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-zinc-900 text-white hover:bg-zinc-700 dark:bg-white dark:text-zinc-900 dark:hover:bg-zinc-200"
                    aria-label="Stop"
                    title="Stop"
                  >
                    <Square className="size-3.5" fill="currentColor" />
                  </button>
                ) : (
                  <button
                    type="submit"
                    disabled={!input.trim() || remaining === 0}
                    className="flex size-8 shrink-0 items-center justify-center rounded-lg bg-emerald-600 text-white hover:bg-emerald-700 disabled:opacity-40"
                    aria-label="Send"
                  >
                    <ArrowUp className="size-4" />
                  </button>
                )}
              </div>
              <p className="mt-1.5 px-1 text-[11px] text-zinc-500 dark:text-zinc-400">
                {scopeLabel} · {remaining === null ? 'unlimited questions' : `${remaining} of ${quota?.limit} questions left today`}. Answers
                can be wrong — check the monitor pages.
              </p>
            </form>
          </>
        )}
      </div>

      <Modal
        open={!!toDelete}
        onClose={closeDeleteDialog}
        title="Delete chat?"
        footer={
          <>
            <Button variant="secondary" onClick={closeDeleteDialog} disabled={deleteBusy}>
              Cancel
            </Button>
            <Button variant="danger" onClick={confirmDelete} loading={deleteBusy}>
              Delete
            </Button>
          </>
        }
      >
        <strong className="font-medium text-zinc-900 dark:text-zinc-100">{toDelete?.title}</strong> and all of its
        messages will be permanently removed.
        {deleteError && (
          <p className="mt-3 text-red-600 dark:text-red-400" role="alert">
            {deleteError}
          </p>
        )}
      </Modal>
    </aside>,
    document.body,
  )
}

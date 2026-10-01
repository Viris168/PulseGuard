/** Fired on window when the monitor count changes without a navigation (e.g. delete from the list). */
export const MONITORS_CHANGED = 'pg:monitors-changed'

/** Fired on window to open Ask AI with a new chat about a page; detail: AskAiPageContext. */
export const OPEN_ASK_AI = 'pg:open-ask-ai'

/** The page a chat is about. `label` is what the panel shows, e.g. "Health". */
export interface AskAiPageContext {
  monitorId?: number
  incidentId?: number
  label: string
}

/** Opens Ask AI from any page ("Ask AI about this"); AppLayout listens. */
export function openAskAi(context: AskAiPageContext) {
  window.dispatchEvent(new CustomEvent<AskAiPageContext>(OPEN_ASK_AI, { detail: context }))
}

/** Fired on window to open the Ask AI panel as it is (no new chat); AppLayout listens. */
export const SHOW_ASK_AI = 'pg:show-ask-ai'

export function showAskAi() {
  window.dispatchEvent(new Event(SHOW_ASK_AI))
}

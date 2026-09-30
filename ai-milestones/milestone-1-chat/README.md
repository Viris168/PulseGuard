# Milestone 1: Ask AI Becomes a Real Chat

**What it does:** Ask AI keeps conversations. Follow-up questions work ("Has that happened
before?" knows what "that" is), answers appear word by word with a **Stop** button, chats are
saved so you can reopen, rename and delete them, and each answer can get a thumbs up or down.

**Why it exists:** Milestone 0.5 answered one question at a time and forgot it immediately. A
real conversation needs memory, and a long answer shouldn't leave you staring at dots.

`PLAN.md` is the plan it was built from (Part 1 explains the new ideas in 10 minutes). These
files are snapshots; the real code lives in `src/` and `frontend/src/`.

---

## The one idea to understand first

**The model has no memory.** Every call starts from zero. A "conversation" is an illusion the app
creates by sending the earlier messages again on every turn:

```
Turn 1 sends: [rules] [data + "Is Health down?"]
Turn 2 sends: [rules] "Is Health down?" → "Yes, 503…" [data + "Has that happened before?"]
```

So the database stores the chat, and each turn rebuilds the prompt from it. Everything else in
this milestone follows from that.

## The flow of one message

```
POST /api/ai/conversations/42/messages  {question, timeZone}
 ├─ your chat?        no → 404          ┐
 ├─ Ask AI on?        no → 403          │ checked BEFORE the stream opens,
 ├─ AI configured?    no → 503          │ so they arrive as plain JSON errors
 ├─ under 50 messages? no → 409         │
 ├─ daily limit left? no → 429          ┘ ← the question is counted here
 ├─ load history (before saving the question!), load snapshot, save the question
 ├─ build prompt: rules + trimmed history + <data>…</data> + "Question: …"
 └─ stream (Server-Sent Events):
      event: delta {"text":"Health is"}
      event: delta {"text":" down."}
      event: done  {"answerId":…,"quota":{"used":1,"limit":5}}
    ending:  finished        → COMPLETE, tokens saved
             Stop / tab closed → PARTIAL, text so far kept, model cancelled
             failed after text → PARTIAL + error event
             failed before text → FAILED + error, question handed back (not counted)
```

## Reading order

### 1. The database: what a chat is
| File | Notice |
|---|---|
| `backend/config/V21__ai_conversations.sql` | Three tables. `ON DELETE CASCADE` chains user → chats → messages → ratings. `CHECK` constraints mean the database itself rejects a bad role, status or rating. |
| `backend/wiring/MessageRole.java`, `MessageStatus.java` | `PARTIAL` and `FAILED` exist so history shows what really happened. |
| `backend/chat/AiConversation.java`, `AiMessage.java`, `AiMessageFeedback.java` | Token counts and model name live on the answer: that's the cost data for later. |
| `backend/chat/AiConversationRepository.java`, `AiMessageRepository.java` | Every lookup takes the user id (`findByIdAndUserId`). There is deliberately no "find by id only". Also `deleteBatchForPlanBefore` for retention. |

### 2. Managing chats (plain REST)
| File | Notice |
|---|---|
| `backend/chat/ConversationService.java` | `owned()`: one helper every method goes through. Another user's chat is the same 404 as a missing one, so ids can't be probed. `rate()`: answers only, rating again replaces. |
| `backend/chat/ChatController.java` | Thin. The five REST endpoints, plus `send()` (section 5). |
| `backend/wiring/ChatNotFoundException.java`, `GlobalExceptionHandler.java` | Search `ChatNotFound`: mapped to 404. |

### 3. Streaming from the model
| File | Notice |
|---|---|
| `backend/ai/ModelStreamEvent.java` | Two kinds of event: `Text` pieces, then one `Finished` with token usage. |
| `backend/ai/ModelCaller.java` | Read `stream()`. `Flux.defer`: nothing runs until someone listens. `.timeout(first, next)`: 20 s to the first piece, 90 s overall. `rawText` (not stripped!) keeps the spaces between pieces; the first version stripped them and printed "Health isdown". |
| `backend/ai/ModelStreamException.java` | The provider's error text can echo your prompt or key, so it's replaced by a safe message and never logged. |
| `backend/ai/AiProperties.java`, `backend/config/application.yaml` | Search `stream-timeout`. |

### 4. The prompt with memory
| File | Notice |
|---|---|
| `backend/chat/ChatPrompt.java` | The heart of "memory". `turns()` pairs questions with answers and drops failed/unanswered ones. `withinBudget()` keeps the newest turns that fit 6,000 characters and stops at the first that doesn't (no gaps). The snapshot rides only on the newest question. |
| `backend/ai/AskAiPrompt.java` | The rules and `facts()` from Milestone 0.5, reused as-is. |
| `backend/ai/PromptText.java` | New `noTags()`: removes `<` `>` but keeps line breaks, so answers' bullet lists survive while `</data>` can't be forged. |

### 5. Sending a message: the streaming endpoint
| File | Notice |
|---|---|
| `backend/chat/ChatService.java` | `send()`: every check, then `quota.acquire`, then a `try` that hands the question back if anything breaks. History is loaded **before** the question is saved. Not `@Transactional`: an answer can take a minute. |
| `backend/chat/ChatMessageStore.java` | Each database step is its own short transaction, so no connection is held while the model writes. The first question becomes the title. |
| `backend/chat/ChatTurn.java` | **Read this one slowly.** One answer in progress. Four ways it can end (see the flow above) can race each other, so `finish()` (`compareAndSet`) lets only the first one save. |
| `backend/chat/ChatEvents.java`, `SseChatEvents.java` | An interface for "send delta/done/error". The app plugs in `SseEmitter`; tests plug in a fake browser that can disconnect. That's how Stop is tested without a browser. |
| `backend/chat/dto/ChatStreamEvents.java` | The JSON inside each `event:`. |
| `backend/ai/AiQuotaPolicy.java` | The Free 5 / Pro 100 / Business 500 logic, moved here so chat and settings share it. |
| `backend/wiring/SecurityConfig.java` | Search `DispatcherType.ASYNC`. When a stream finishes, Spring re-dispatches the request and the login filter doesn't run again. Without this rule every answer ended in "Access Denied" (a test proved it). |

### 6. Housekeeping and the old endpoint
| File | Notice |
|---|---|
| `backend/wiring/RetentionService.java` | Search `chatCutoff`: chats unused for the plan's history days (Free 7, Pro 90) are deleted nightly, in batches. |
| `backend/ai/AskAiService.java`, `AskAiController.java` | Now only consent and quota: the one-shot `/api/ai/ask` from Milestone 0.5 was removed. |
| `deploy/Caddyfile` | The chat stream is left out of compression, which could otherwise hold pieces back. |

### 7. Frontend
| File | Notice |
|---|---|
| `frontend/http.ts` | `apiFetch()` split out of `api()`: the stream needs the raw response but still gets the login token and refresh-on-401. |
| `frontend/ai.ts` | `streamMessage()` reads the stream with `fetch` + `getReader()`, because `EventSource` can't send the login token. `createSseParser()` buffers until a blank line, because the network can cut an event anywhere, even mid-word. Stop comes back as `{kind: 'stopped'}`, not an error. |
| `frontend/AskAiPanel.tsx` | `ask()`: adds an empty "streaming" bubble, creates the chat on the first question, grows the text in `onDelta`. `AbortController` is Stop; closing the panel aborts too. `RichText` groups bullet and text lines (a paragraph can be "Two incidents:" followed by a list). |

### 8. Tests
| File | Notice |
|---|---|
| `tests/AiChatRepositoryTest.java` | User A's queries never return user B's rows; cascades delete what they should. |
| `tests/ConversationApiIntegrationTest.java` | `anotherUsersChatIsNotFoundEverywhere`: Bob tries to read, rename, delete and rate Alice's chat; all 404, nothing changes. |
| `tests/ModelCallerStreamTest.java` | A fake model plays back chunks: order and spaces kept, both timeouts, safe errors, Stop cancels the provider. |
| `tests/ChatPromptTest.java` | Pure logic: budget, no gaps, failed turns dropped, `</data>` in a question can't forge data. |
| `tests/ChatTurnTest.java` | Every ending in the flow above, with a fake model (`Sinks`) and a fake browser that can drop the connection. |
| `tests/ChatStreamApiIntegrationTest.java` | The real endpoint end to end. **Turn 2's prompt contains turn 1's question and answer**: memory, proven. |
| `tests/AskAiApiIntegrationTest.java` | Milestone 0.5's privacy tests, now asking through the chat: only shared monitors, never other users', never URLs. |
| `tests/HousekeepingIntegrationTest.java` | Search `askAiChats`: old Free chat deleted with its messages, recent one and Pro one kept. |
| `frontend/ai.test.ts` | The SSE parser with nasty chunking; `streamMessage` done / error / 429 / Stop. |
| `frontend/AskAiPanel.test.tsx` | Streaming, follow-up in the same chat, Stop, upgrade, Try again, thumbs, reopen, rename, delete dialog. |

---

## Key lessons

1. **The model has no memory; the app resends history.** So history needs a budget, or every
   turn costs more than the last.
2. **Refuse before you stream.** Once a stream has started you can't send a clean HTTP error.
3. **Never hold a database connection while a model writes.** Short transactions before and after.
4. **When several things can end the same job, let exactly one win** (`compareAndSet`).
5. **Test the hard endings with fakes you control:** a fake model you push pieces into, a fake
   browser that disconnects. Real ones can't be made to fail on cue.
6. **Streaming text is not whole text.** Don't trim pieces; buffer until an event is complete.

## Try it yourself (exercises)

1. In `ChatPrompt`, change `HISTORY_BUDGET_CHARS` to 300. Ask three questions, then "What did I
   ask first?" What happens, and why?
2. Ask a long question and press Stop after two words. Reload and reopen the chat: what's saved?
   Then find where that happens in `ChatTurn`.
3. Temporarily remove the `DispatcherType.ASYNC` line in `SecurityConfig` and run
   `ChatStreamApiIntegrationTest`. Read the error, then put it back.
4. Add a test to `ChatPromptTest`: a chat where every answer failed sends only the new question.
5. In `createSseParser`, what breaks if you split on `\n` without buffering? Write the test
   first in `ai.test.ts`.

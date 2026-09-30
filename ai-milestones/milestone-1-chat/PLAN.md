# Milestone 1: Ask AI Becomes a Real Chat

Today Ask AI is one question, one answer: it forgets everything after each reply, you wait for
the whole answer, and closing the panel loses it. Milestone 1 makes it a chat:

- **Follow-up questions work.** "Why is Health down?" then "Has that happened before?" knows what
  "that" means.
- **Answers appear word by word** while the model writes them, with a **Stop** button.
- **Conversations are saved.** Close the panel, come back tomorrow, pick up where you left off.
- **Thumbs up/down** on each answer, so bad answers can be found and fixed.

The data the model sees stays the same as now (the snapshot of shared monitors). Letting the
model fetch exactly what it needs ("uptime on March 3rd") is Milestone 2.

Estimate: **6–8 working days**.

---

## Part 1: The new ideas (10-minute read)

### The model has no memory

Every call to Gemini or Claude starts from zero. A "conversation" is an illusion the app creates:
on every new question, **we send the earlier messages again**, then the new question.

```
Turn 1 sends: [rules] [data] "Why is Health down?"
Turn 2 sends: [rules] [data] "Why is Health down?" → "It returns 503…" → "Has that happened before?"
```

So the database stores the conversation, and each turn rebuilds the prompt from it.

### History costs money, so it has a budget

Resending history means every turn costs more than the last. Two limits keep this bounded:

- **History budget:** send only the most recent messages that fit (about 6,000 characters,
  roughly 1,500 tokens). Older messages stay saved and visible, but the model no longer sees them.
- **Conversation cap:** 50 messages, then "Start a new chat". Long chats also drift off topic.

### Streaming: sending the answer while it's written

A model writes a few words at a time. Instead of waiting 3–10 seconds for all of it, the server
forwards each piece to the browser the moment it arrives. We use **Server-Sent Events (SSE)**:
one HTTP response that stays open and carries small messages:

```
event: delta   data: {"text":"Health is"}
event: delta   data: {"text":" down: it returns 503"}
event: done    data: {"messageId":81,"quota":{"used":3,"limit":5}}
```

`delta` = more text, `done` = finished and saved, `error` = something went wrong (friendly message).

Two details matter:
- The browser's built-in `EventSource` can't send our login token (`Authorization: Bearer …`),
  so the frontend reads the stream with plain `fetch` instead.
- In production Caddy sits in front of the app. It must pass each piece on immediately rather
  than collect the whole response. Caddy does this for `text/event-stream` responses by itself;
  we check it once after deploying.

### Stop, and what gets saved

When you click Stop (or close the tab), the browser drops the connection. The server notices,
tells the model to stop, and **saves what was written so far** with status `PARTIAL`. Every
message has a status: `COMPLETE`, `PARTIAL` or `FAILED`, so history shows what really happened.

---

## Part 2: What we reuse, what's new

Most of the hard parts already exist from Milestone 0 and Ask AI:

| Already built | Reused as |
|---|---|
| `AiAccess` + `/api/ai/access` | Unchanged. Chat reads only shared monitors |
| `AiQuestionQuota` (Redis) + plan limits | Unchanged. **One chat message = one question** (Free 5/day, Pro 100) |
| `AskAiSnapshotLoader` + `AskAiPrompt` rules | The data and rules for every chat turn |
| `ModelCaller` (timeout, never throws, logs usage) | Gains a `stream(…)` method next to `call(…)` |
| `PromptText` fencing, session-only `/api/ai/**` | Unchanged |
| `AskAiPanel` UI (access screens, bubbles, RichText) | Gains a conversation list, streaming, Stop, thumbs |

New:

| New | What it is |
|---|---|
| `V21__ai_conversations.sql` | `ai_conversations`, `ai_messages`, `ai_message_feedback` |
| `ChatService` | Ownership → consent → quota → save question → build prompt → stream → save answer |
| `ChatController` | Conversation CRUD + the streaming endpoint + feedback |
| `ChatPrompt` | Rules + fresh snapshot + trimmed history + the new question |
| Frontend `streamMessage()` | `fetch` + stream reader + `AbortController` for Stop |
| Retention | Old conversations deleted by the nightly housekeeping job |

---

## Part 3: Decisions (recommendations, change any you disagree with)

| Decision | Recommendation | Why |
|---|---|---|
| **Data per turn** | A **fresh snapshot on every turn**, attached to the newest question only. Earlier turns are sent as plain text. | Answers use current data ("is it still down?"), and the snapshot isn't paid for once per old message. |
| **Titles** | The first question, cut to 60 characters. Renamable. | Free and instant. AI-written titles would cost a model call per chat for little gain; can come later. |
| **What counts toward the daily limit** | Each message sent. A stopped answer still counts (the model already did the work); a failed one is handed back. | Matches today's Ask AI and the plan cards. |
| **History budget** | Newest messages up to ~6,000 characters; cap 50 messages per conversation. | Keeps each turn's cost roughly flat. |
| **Retries** | No automatic retry. On failure: `error` event, question handed back, "Try again" button. | A retry halfway through an answer would repeat text; one clear error is simpler. |
| **Timeouts** | 20 s to the first word, 90 s for the whole answer. | A stuck provider can't hold a connection forever. |
| **Retention** | Conversations older than the plan's history (Free 7 days, Pro 90, Business 365) are deleted nightly. Account deletion removes all of them (database cascade). | Same promise as check history; less stored customer data. |
| **Old `POST /api/ai/ask`** | Removed once the panel uses the chat endpoints. | One path to maintain and test. |

---

## Part 4: Database (V21)

```sql
CREATE TABLE ai_conversations (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title       VARCHAR(100) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_conversations_user ON ai_conversations (user_id, updated_at DESC);

CREATE TABLE ai_messages (
    id               BIGSERIAL   PRIMARY KEY,
    conversation_id  BIGINT      NOT NULL REFERENCES ai_conversations(id) ON DELETE CASCADE,
    role             VARCHAR(10) NOT NULL,   -- USER / ASSISTANT
    content          TEXT        NOT NULL,
    status           VARCHAR(10) NOT NULL,   -- COMPLETE / PARTIAL / FAILED
    input_tokens     INT,
    output_tokens    INT,
    model            VARCHAR(100),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_messages_conversation ON ai_messages (conversation_id, id);

CREATE TABLE ai_message_feedback (
    message_id  BIGINT      PRIMARY KEY REFERENCES ai_messages(id) ON DELETE CASCADE,
    rating      SMALLINT    NOT NULL,        -- 1 = thumbs up, -1 = thumbs down
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`role` and `status` are Java enums stored as strings, per CLAUDE.md. Token counts come from the
provider's final stream chunk; they let us see cost per user later without a separate table.

---

## Part 5: API

All under `/api/ai`, session-only, scoped by the logged-in user. Another user's conversation id
is a 404, exactly like one that doesn't exist.

| Method | Path | Does |
|---|---|---|
| `GET` | `/conversations` | Your conversations, newest first (id, title, updatedAt) |
| `POST` | `/conversations` | Start one (empty; the title comes from the first message) |
| `GET` | `/conversations/{id}/messages` | Its messages, oldest first |
| `PATCH` | `/conversations/{id}` | Rename |
| `DELETE` | `/conversations/{id}` | Delete it and its messages |
| `POST` | `/conversations/{id}/messages` | Send `{question, timeZone}`; the response is the SSE stream |
| `PUT` | `/messages/{id}/feedback` | `{rating: 1 \| -1}`; only on your own assistant messages |

The streaming endpoint checks consent, quota and ownership **before** opening the stream, so
those errors come back as ordinary 403/429/404 JSON the panel already knows how to show.

---

## Part 6: How one message flows

```
POST /conversations/42/messages  {question, timeZone}
 ├─ conversation 42 is yours?                 no → 404
 ├─ Ask AI on? model configured?              no → 403 / 503
 ├─ conversation under 50 messages?           no → 409 "Start a new chat"
 ├─ quota.tryAcquire                          no → 429
 ├─ save USER message (short transaction)
 ├─ load snapshot + last messages within budget (short read-only transaction)
 ├─ open SSE, call modelCaller.stream(prompt)
 │    each piece → event: delta
 │    finished   → save ASSISTANT (COMPLETE, tokens) → event: done
 │    Stop/disconnect → cancel model → save PARTIAL
 │    failure before any text → save FAILED, hand question back → event: error
 └─ no database connection is held while the model writes
```

---

## Part 7: Steps

Each step ends with something you can run or test.

### Step 1: Database and entities (0.5 day)
- `V21__ai_conversations.sql` as in Part 4.
- `AiConversation`, `AiMessage`, `AiMessageFeedback` entities; `MessageRole`, `MessageStatus`
  enums in `enumeration/`; repositories with every lookup scoped by `userId`
  (e.g. `findByIdAndUserId`).
- **Test:** repository test (Testcontainers): user A's queries never return user B's rows.

### Step 2: Conversation CRUD (1 day)
- `ConversationService` + `ChatController`: list, create, messages, rename, delete.
- `ConversationNotFoundException` → 404 in `GlobalExceptionHandler`.
- **Test:** integration test for each endpoint, including every one with another user's id → 404,
  and an API key → 403.

### Step 3: Streaming in `ModelCaller` (1 day)
- `stream(Prompt, subject)` returning a `Flux<String>` of text pieces plus the final token usage.
  Same rules as `call`: nothing when AI is off, 20 s to first piece, 90 s total, never logs text.
- **Test:** with a fake `ChatModel` whose `stream()` emits pieces: order kept; timeout to first
  piece; cancellation stops the fake model.

### Step 4: `ChatPrompt` (0.5 day)
- System rules (the Ask AI rules plus "use earlier messages for context").
- Fresh `<data>` snapshot + history trimmed to the budget + the new question.
- **Test:** pure unit tests: newest messages kept first; budget respected; history text is fenced
  with `PromptText` too (a user could paste `</data>` into a question); failed messages skipped.

### Step 5: Send a message: the streaming endpoint (1.5 days)
- `ChatService.send(…)` following Part 6, using `SseEmitter` (Spring MVC's SSE support).
- Set `spring.mvc.async.request-timeout` to 120 s so Spring doesn't cut long answers.
- Title from the first question; `updated_at` bumped on each message.
- **Test (integration, fake streaming model):**
  - events arrive as `delta…delta, done`; both messages saved `COMPLETE` with tokens;
  - disconnect mid-answer → `PARTIAL` saved with the text so far;
  - model fails before any text → `FAILED`, quota handed back, `error` event;
  - 50-message cap → 409; quota → 429; consent off → 403; another user's conversation → 404;
  - the prompt of turn 2 contains turn 1's question and answer.

### Step 6: Frontend (2 days)
- `api/ai.ts`: `listConversations`, `getMessages`, `rename`, `delete`, `sendFeedback`, and
  `streamMessage(id, question, { onDelta, signal })` that reads the SSE stream with `fetch`.
  Token refresh on 401 must still work before the stream starts (reuse `http.ts`).
- `AskAiPanel`:
  - a conversation list (drawer or back arrow), "New chat";
  - the answer grows as `delta`s arrive; Send turns into **Stop** while writing;
  - stopped answers show "Stopped"; failed ones show the error and "Try again";
  - thumbs up/down under finished answers;
  - keep the access screens, quota line and RichText as they are.
- **Test (vitest):** stream parsing (pieces split across chunks, `done`, `error`); Stop aborts the
  request and keeps the partial text; list/rename/delete; thumbs.

### Step 7: Retention, cleanup, docs (0.5 day)
- Nightly `HousekeepingJob` deletes conversations older than the plan's history days.
- Remove `POST /api/ai/ask` and its tests once the panel no longer uses it.
- Update `AI_PLAN.md`, `CLAUDE.md` (new endpoints, the SSE rule), `DEPLOY.md` (Caddy note).
- **Test:** housekeeping deletes an old Free-plan conversation and keeps a recent one.

### Step 8: Try it for real (0.5 day)
- Locally with Gemini: follow-ups, Stop mid-answer, reload and reopen, hit the daily limit.
- After deploying: confirm answers stream word by word through Caddy, not all at once.

---

## Done when

- [ ] A follow-up question uses the earlier messages ("has that happened before?").
- [ ] Answers appear word by word; Stop works and keeps the partial answer.
- [ ] Conversations survive closing the panel and reloading; rename and delete work.
- [ ] Every chat message counts toward the daily limit; failed ones are handed back.
- [ ] Each answer's tokens are saved.
- [ ] Another user's conversations and messages are always 404 (tests).
- [ ] Old conversations are deleted by plan retention; account deletion removes all.
- [ ] `./mvnw test` and `npm test` green.

## Risks to watch

- **Security on the stream.** Spring Security checks requests again when an async response
  finishes. If streams end in 403 or a login error mid-answer, the async dispatch needs allowing in
  `SecurityConfig`, the same way `ERROR` dispatches already are. Step 5's tests will show it.
- **Proxy buffering.** If answers arrive in one lump in production, set `flush_interval -1` on
  the chat route in `deploy/Caddyfile`.
- **Gemini free tier.** Low per-minute and daily limits per model (5 a minute on `gemini-3.6-flash`
  when we checked). A busy chat can hit them; the user sees "try again", and the question isn't counted.
- **Cost growth.** History makes later turns cost more. The budget and the 50-message cap bound
  it; the saved token counts will show real numbers after a week.

## Not in this milestone (Milestone 2)

- Tools that fetch exactly what's asked (any date range, a specific incident's checks).
- "Ask AI" buttons on monitor and incident pages that open a chat about that item.
- AI-written titles, conversation search, sharing chats.

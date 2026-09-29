# AI Chat Platform: End-to-End Plan (Phases 1–4)

Sep 28, 2026 · @RisVi

Build one Spring Boot chat platform and add four assistants on top of it, one phase at a time, in roughly 5–9 weeks for one experienced developer. The assistants share one core (conversations, model calls, streaming, history) and differ only in their instructions, data access, and tools. Each phase ends with something working, so you can stop, ship, or reprioritize at any point.

## Before you start: decisions and setup

Settle these four choices first, because they affect every phase.

- **Chat model provider.** Pick a primary provider (for example Claude via the Anthropic API) and access it through Spring AI, so switching or mixing providers later is a configuration change. Use two tiers: a stronger model for complex answers (documents, PulseGuard analysis) and a cheaper, faster one for simple tasks (titles, the public widget).
- **Embedding model.** Needed in Phase 3, but choose it early: changing it later means re-processing every document. Options include Voyage AI, OpenAI embeddings, or a self-hosted model. Record its vector dimension, since the database column must match it.
- **Frontend approach.** With Thymeleaf, a small amount of JavaScript handles streaming. With React or Vue, build the chat UI as a reusable component so Phase 4's widget can reuse it.
- **Infrastructure.** Java 21, Spring Boot 3, Spring AI, PostgreSQL with pgvector, Redis for rate limiting, Flyway for migrations, and file storage (local disk in development, S3-compatible in production). API keys live in environment variables or a secrets manager, never in code.

## Phase 1: Core chat platform and general assistant (1–2 weeks)

A logged-in user can chat with a general assistant, watch responses stream in live, and return to past conversations. This phase builds the foundation every later assistant reuses.

### What gets built

- **Assistant profiles.** An `assistants` table seeded with one row, the general assistant. Each profile holds a system prompt, model name, generation settings, allowed tools (empty for now), linked knowledge bases (empty for now), and who can use it. Later phases add rows, not services.
- **Conversation storage.** `conversations` (id, user, assistant, title, timestamps) and `messages` (id, conversation, role, content, token counts, status, timestamps). Status is `complete`, `partial` (stopped or disconnected), or `failed`, so history reflects what actually happened.
- **Chat Orchestrator.** For every message it:
  1. Verifies the user owns the conversation.
  2. Checks the user's plan usage limit.
  3. Saves the user's message.
  4. Loads recent history and builds the prompt.
  5. Calls the model in streaming mode and forwards each piece of text to the browser.
  6. Saves the full reply and records token usage when the stream ends.
- **Memory strategy.** Send only the most recent messages that fit a token budget. Add summarization of older messages later for very long conversations.
- **Streaming endpoint.** Server-Sent Events with three event types: `delta` (new text), `done` (saved message ID and token usage), and `error` (a friendly message). When the user clicks stop or closes the tab, cancel the model call and save the partial message.
- **REST API.** Create, list, rename, and delete conversations; fetch a conversation's messages; send a message (the streaming endpoint).
- **Automatic titles.** After the first exchange, the cheap model generates a short title in the background.
- **Usage tracking and limits.** A `usage_records` table with input tokens, output tokens, and estimated cost per user per day. Enforce daily limits per subscription tier with a clear message, plus a global daily spending alert for you.
- **Error handling.** Retry rate-limit, timeout, and overload errors a few times with increasing delays, then show a friendly error. Never expose stack traces or raw provider errors.
- **Chat UI.** Conversation sidebar, sanitized Markdown rendering (so the model can't inject HTML or scripts), text box with send and stop buttons, and a new-chat button.

### Testing

Unit tests with a mocked model (no cost, no network). Integration tests with Testcontainers running real Postgres. A list of about 20 manual prompts covering normal questions, very long messages, stopping mid-stream, and hitting the usage limit.

### Done when

- [ ] A user can start a chat, see streamed responses, and stop a response.
- [ ] A user can return later to the same conversation.
- [ ] Usage limits show a clear message, and every request's tokens are recorded.
- [ ] No user can see another user's conversations.

## Phase 2: PulseGuard assistant (1–2 weeks)

Inside the dashboard, users ask about their own monitors in plain language ("Why was my payment API down last night?") and get answers from their real data.

### What gets built

- **Read-only tools** the model can call:
  - `list_monitors`: name, URL, current status, uptime %.
  - `get_monitor_details`: one monitor's configuration and state.
  - `get_incidents`: incidents in a time range with start, end, duration, cause.
  - `get_uptime_stats`: uptime % over a period.
  - `get_response_time_summary`: average, median, slowest response times (aggregated, not raw rows).
  - `get_recent_failures`: the last few failed checks with errors, capped.
- **Tool design rules** (more important than the tools themselves):
  - The user's identity comes from the server's security context, never from a tool parameter. The model picks which monitor; your code decides whose monitors are visible. A monitor ID the user doesn't own returns "not found."
  - Return summaries, aggregated in SQL, not thousands of raw rows.
  - Cap every result size and tell the model when results were truncated.
  - Return times in the user's time zone, clearly labeled.
- **Assistant profile.** A second assistant row with these tools and a prompt telling it to answer only from tool results, say clearly when there's no data, include specific times and numbers, and stay on monitoring topics.
- **Tool-call limit.** About five tool calls per message, so a confused model can't loop and run up costs.
- **Audit logging.** Store each tool call and result with the message, to debug wrong answers.
- **Context-aware entry points.** A chat panel plus "Ask AI" buttons on specific pages; on an incident page, chat opens focused on that incident.
- **Actions that change things (optional).** For tools like pausing or creating a monitor, use propose-and-confirm: the model proposes, the UI shows a confirmation card, and the backend executes only after the user clicks confirm.

### Testing

Seed a test account with realistic monitors, incidents, and check history, and write about 20 scenario questions with known answers. Add a cross-tenant test: logged in as user A, ask about user B's monitor and confirm nothing leaks.

### Done when

- [ ] Answers use correct numbers and times from the user's own data.
- [ ] The assistant says "I don't have data for that" instead of inventing answers.
- [ ] The cross-tenant test passes.
- [ ] Every tool call is logged.

## Phase 3: Company documents assistant with RAG (2–3 weeks)

Users upload documents (policies, manuals, runbooks) and the assistant answers from them with citations. Most of the time goes to quality tuning, not initial building.

### What gets built

- **Knowledge bases.** A `knowledge_bases` table groups documents (for example "HR policies"). Each assistant links to one or more, and each has access rules.
- **Documents and chunks.** `documents` (file name, storage location, status, uploader, access group, timestamps) and `document_chunks` (text, embedding vector, document ID, page or section, heading, access group), with an HNSW vector index in pgvector.
- **Ingestion pipeline**, run in the background:
  1. Parse to text with Apache Tika (PDF, Word, HTML, plain text).
  2. Clean out repeated headers, footers, and page numbers.
  3. Chunk into roughly 500–800 tokens with overlap, splitting at headings and paragraphs, keeping the section heading with each chunk.
  4. Embed chunks in batches.
  5. Store chunks with vectors and metadata.
- **Status tracking.** Each document is `pending`, `processing`, `ready`, or `failed` with a reason, shown in the UI. Replacing a document deletes and re-processes its chunks; deleting it deletes its chunks.
- **Retrieval**, for each question:
  1. Rewrite follow-ups ("What about for contractors?") into standalone questions using the cheap model and recent history.
  2. Search for similar chunks, filtered in the database query by the assistant's knowledge bases and the user's access groups.
  3. Consider hybrid search: combine vector search with Postgres full-text search, which handles exact terms like error codes and policy numbers.
  4. Apply a relevance threshold; if nothing qualifies, answer "not found in the documents" instead of guessing.
  5. Pass chunks to the model labeled with document and section.
- **Answer rules.** Answer only from sources, cite each claim, say clearly when documents don't cover the question, and treat document text as information, never as instructions (a document could contain "ignore your instructions").
- **Citations UI.** Sources under each answer with document name and page or section, linking to the file.
- **Admin UI.** Create knowledge bases, upload, see status, retry failures, delete. Enforce file size, file types, and per-plan storage limits.

### Testing and evaluation

Build a golden set of 30–50 questions from real documents, each with the correct answer and source. Measure retrieval accuracy (did the right chunk appear?) and answer accuracy (correct and properly cited?). Rerun it after every change to chunking, search settings, or prompts. Also test that users without access to a knowledge base never receive its content.

### Done when

- [ ] Uploaded documents reliably reach `ready`.
- [ ] Most golden-set questions are answered correctly with correct citations.
- [ ] Unanswerable questions get an honest "not found."
- [ ] Permission filtering is verified by tests.

## Phase 4: Customer support widget (1–2 weeks)

A chat widget embedded on any website with one script tag, answering visitors from a knowledge base and handing off to a human when needed. Design it multi-tenant, so each customer (or you) gets their own widget, knowledge base, and branding.

### What gets built

- **Widget configuration.** A `widgets` table: owner, public widget key, allowed domains, assistant profile, knowledge bases, branding (name, colors, greeting), handoff email, monthly message quota.
- **Embed mechanism.** A script tag adds a chat button that opens the chat in an iframe on your domain, isolating styles and data from the host page.
- **Public API.** On open, the widget exchanges its key for a short-lived session token after the server checks the request's domain. Messages go through the same streaming orchestrator with a restricted assistant profile. Visitors are anonymous by default, with optional email capture.
- **Abuse and cost protection** (the most important part, since the endpoint is public):
  - Rate limits per IP, per session, and per widget, in Redis.
  - Monthly message quota per widget, tied to the owner's plan.
  - Domain checks stop casual misuse but can be faked outside a browser; rate limits and quotas are the real protection.
  - Optional bot check (such as Cloudflare Turnstile) on open.
  - Cheaper model and shorter maximum responses.
  - Only two tools: knowledge base search and ticket creation. No access to any account data.
- **Human handoff.** Triggered when the visitor asks for a person, the assistant finds no answer, the visitor seems frustrated, or several turns pass without resolution. Collect the visitor's email, create a ticket, and email the transcript to the owner's support address. Zendesk, Intercom, or Slack integrations can come later.
- **Owner dashboard.** Transcripts, visitor ratings, and an "unanswered questions" report showing which documentation to write next.
- **Privacy.** Clearly disclose the AI, set a transcript retention period, support deletion requests (GDPR), and document what's stored in the privacy policy.

### Testing

Test from allowed and disallowed domains. Load-test simulated abuse to confirm rate limits and quotas hold. Verify adversarial prompts can't reach account data, and walk through a full handoff from question to ticket email.

### Done when

- [ ] An owner can create a widget, paste the script, and visitors get answers from their knowledge base.
- [ ] Unanswered questions reach a human by email.
- [ ] Abuse is contained by limits.
- [ ] Owners can review all conversations.

## Across all phases

- **Observability.** Log every model request with latency, tokens, cost, tool calls, and retrieved chunks. Track daily cost, error rate, and response time per assistant on an internal dashboard.
- **User feedback.** Thumbs up and down on every assistant message from Phase 1. Poorly rated answers become new test cases.
- **Prompt versioning.** Store system prompts in the database with version numbers, and record which version produced each message.
- **Feature flags.** Roll out each assistant to yourself first, then a few users, then everyone.

## Timeline and main risks

Total: roughly 5–9 weeks for one experienced developer.

| Phase | Deliverable | Estimate |
| --- | --- | --- |
| 1 | Core platform, streaming chat, history, usage limits | 1–2 weeks |
| 2 | PulseGuard assistant with read-only tools | 1–2 weeks |
| 3 | Documents assistant with RAG, citations, evaluation | 2–3 weeks |
| 4 | Embeddable support widget with handoff | 1–2 weeks |

- **Cost overruns** from long conversations, tool loops, or widget abuse. Usage limits, tool-call caps, and rate limits from the start address this.
- **Poor document answers**, the most common reason RAG projects disappoint. Build the golden evaluation set early in Phase 3.
- **Data leaks between users**, the most serious risk. Server-side identity in tools and permission filtering in search queries, both verified by tests.

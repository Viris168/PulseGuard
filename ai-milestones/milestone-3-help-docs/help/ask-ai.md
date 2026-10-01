---
title: Ask AI
summary: Ask questions about your monitors in plain words; what Ask AI can see, how it looks things up, limits and privacy.
describes: ai/chat/ChatService.java, ai/tools/MonitorTools.java, ai/tools/GuardedToolCallback.java, ai/AiQuotaPolicy.java, billing/PlanLimits.java, frontend/src/components/ai/AskAiPanel.tsx
---

Ask AI answers questions about your own monitors in plain words: "Why did Payments API go down
last night?", "What was Health's uptime in the last 30 days?", "Show me yesterday's failed
checks", "What does 503 mean?" Open it with **Ask AI** at the top of any page.

## Turning it on

Ask AI is **off until you enable it**, and you choose what it may read:

- **All monitors**, including monitors you add later; or
- only the monitors you select.

It can read monitor names and settings, check and ping history, response times, incidents and
alert delivery history. It can't change, pause or delete anything, can't see passwords, API
keys, ping URLs or webhook URLs, and can't read monitors you didn't select. Change this any time
with the settings button in the Ask AI panel.

## How it finds answers

Ask AI starts with a snapshot of your shared monitors: their current status, the last 24 hours
and 7 days, and recent incidents. For anything else it **looks things up**: uptime or response
times for any dates your plan keeps, incidents in a period, one incident's full timeline, or the
failed checks on a given day.

Each lookup shows above the answer, for example **"Checked uptime for Health, 2026-09-01 to
2026-09-30"**, so you can see exactly what it based the answer on. If a lookup looks wrong (the
wrong monitor or dates), ask again more precisely. It makes at most 5 lookups per question.

It can only look up what your plan still keeps: on Free, a question about two months ago gets
"that's older than your plan keeps".

## Asking about a page

On a monitor's page, **Ask AI** starts a chat about that monitor; on an incident's page, **Ask AI
about this** starts one about that incident, with suggested questions such as "Why did this
happen?" or "Has this happened before?".

## Chats

Ask AI remembers the conversation, so follow-ups like "Has that happened before?" work. Your
chats are listed in the panel, where you can reopen, rename or delete them. A chat holds up to 50
messages; after that, start a new one. Chats you haven't used for longer than your plan's history
are deleted.

Answers appear as they're written; **Stop** ends one early. Rate answers with 👍 or 👎 to help
improve Ask AI.

## How many questions

| Plan | Questions per day |
|---|---|
| Free | 5 |
| Pro | 100 |
| Business | Unlimited (fair use: 500) |

The count resets at midnight UTC. A question counts once however many lookups it needs, and one
that fails before any answer appears isn't counted. The panel shows how many you have left.

## Privacy and accuracy

To write an answer, your question and the data it needs (monitor names, figures, incidents,
error messages) are sent to the AI provider PulseGuard uses. Monitor URLs, headers, ping URLs,
alert targets and API keys are never sent. Ask AI isn't available through API keys, only when
you're signed in.

**Answers can be wrong.** Ask AI shows what it looked up so you can check, and the monitor and
incident pages always have the real figures.

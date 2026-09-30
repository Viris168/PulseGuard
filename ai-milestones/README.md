# AI in PulseGuard: Learning Folder

One folder per milestone, so you can study how the AI features were built, step by step.

| Folder | What it is | Status |
|---|---|---|
| [`00-overview/`](00-overview/) | The original Phases 1–4 plan and the review + overall plan (`AI_PLAN.md`) | Reference |
| [`milestone-0-incident-summary/`](milestone-0-incident-summary/) | The model writes a short summary on each incident page | ✅ Built |
| [`milestone-0.5-ask-ai/`](milestone-0.5-ask-ai/) | The Ask AI panel answers one question from your real data | ✅ Built |
| [`milestone-1-chat/`](milestone-1-chat/) | Ask AI becomes a chat: history, streaming, Stop | ✅ Built (production streaming check after deploy) |

## How to use it

1. Read `00-overview/AI_PLAN.md` once for the big picture.
2. For each milestone, open its `README.md` first. It lists the files **in reading order** and
   says what to notice in each one.
3. Then read its `PLAN.md` if there is one: why things were done that way.

## Important

- **These are copies**, taken on 2026-09-30. The real, running code lives in `src/` and
  `frontend/src/`. When the code changes later, these copies don't. That's on purpose: each
  folder shows the code as it was when that milestone was done.
- Some files appear in more than one milestone (e.g. `ModelCaller.java`, `PromptText.java`),
  because a later milestone reused them. Compare the copies to see what changed.
- Files like `SecurityConfig.java` or `PlanLimits.java` are copied whole, but only a few lines
  matter for AI. Each README tells you what to search for (Ctrl+F).
- Maven and Vite ignore this folder, so the copies never affect the build or tests. VS Code's Java
  extension may underline them as "not on the classpath"; that's harmless.

# Milestone 1: Ask AI Becomes a Real Chat

**Status: planned, not built yet.** Only `PLAN.md` is here for now. When it's built, the code
and tests will be copied into `backend/`, `frontend/` and `tests/` like the earlier milestones.

**What it will do:**
- follow-up questions that remember the conversation;
- answers that appear word by word (streaming), with a Stop button;
- saved conversations you can reopen, rename and delete;
- thumbs up/down on answers.

## Read before building

`PLAN.md` Part 1 explains the new ideas in 10 minutes:
- why the model has **no memory** and the app resends history each turn;
- why history needs a **budget** (every turn costs more);
- how **streaming** works (Server-Sent Events) and why the frontend uses `fetch`, not `EventSource`;
- what **Stop** saves.

## What it builds on

Most of it reuses Milestone 0.5: access settings, daily limit, the data snapshot, `ModelCaller`
and the prompt rules. Compare with `../milestone-0.5-ask-ai/` as you read the plan.

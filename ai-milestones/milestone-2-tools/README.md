# Milestone 2: Ask AI Looks Things Up (Tools)

**Status: planned, not built yet.** Only `PLAN.md` is here for now. When it's built, the code
and tests will be copied into `backend/`, `frontend/` and `tests/` like the earlier milestones.

**What it will do:**
- answer about any date range in your plan's history ("uptime in August");
- fetch details on demand: an incident's timeline, the failed checks;
- show what it looked up under each answer;
- start a chat from a monitor or incident page with "Ask AI about this".

## Read before building

`PLAN.md` Part 1 explains the new ideas in 10 minutes:
- what a **tool** is: the model asks, **our code** runs it;
- why every tool round is **another model request** (cost, speed, rate limits);
- why the **server** decides whose data a tool sees, never the model;
- why tool results are **data, not instructions**.

## What it builds on

The chat from Milestone 1 (streaming, `ChatTurn`, `ChatPrompt`, the quota) and the access
settings and snapshot from Milestone 0.5. Compare with `../milestone-1-chat/` as you read the plan.

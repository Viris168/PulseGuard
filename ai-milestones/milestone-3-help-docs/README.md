# Milestone 3: Ask AI Answers From PulseGuard's Help Docs (RAG)

**Status: planned, not built yet.** Only `PLAN.md` is here for now. When it's built, the help
docs, code and tests will be copied into `help/`, `backend/`, `frontend/` and `tests/` like the
earlier milestones.

**What it will do:**
- give PulseGuard real help docs, readable on a Documentation page;
- let Ask AI answer "how do I…" questions from them, with the sources linked under the answer;
- say "the docs don't cover that" instead of guessing.

## Read before building

`PLAN.md` Part 1 explains the new ideas in 10 minutes:
- **RAG**: retrieve the few passages that matter, then generate the answer from them;
- **embeddings**: meaning as a list of numbers, so similar meanings are near each other;
- **chunking**: one piece per section, with its title, so each piece makes sense alone;
- **hybrid search**: meaning (pgvector) plus exact words (Postgres full-text) for things like "502";
- **evaluation**: a golden set of questions, measured after every change instead of tuning by feel.

## What it builds on

Milestone 2's tool loop and guard: docs search is just one more tool, `search_help_docs`.
Compare with `../milestone-2-tools/` as you read the plan.

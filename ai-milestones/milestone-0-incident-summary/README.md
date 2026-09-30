# Milestone 0: AI Incident Summary

**What it does:** open an incident page and the model writes 2–4 plain sentences: what failed,
for how long, the likely cause, whether alerts went out, and how it ended. If AI is off or the
provider fails, the page shows a built-in summary instead, so it never breaks.

**Why it came first:** it's one question, one answer, with no chat UI. It teaches every piece
later features need (provider setup, prompts, cost, failures, prompt injection, tests) without the
hard parts of a chat.

`PLAN.md` in this folder is the full plan, with a beginner primer (tokens, system vs user
message, temperature, Spring AI) in Part 1. Read that first if the words below are new.

---

## Reading order

### 1. How the app talks to the model
| File | Notice |
|---|---|
| `backend/config/pom.xml` | Search `spring-ai`. The BOM picks versions; two starters: Anthropic and Google GenAI. |
| `backend/config/application.yaml` | Search `ai:`. `spring.ai.model.chat` chooses the provider (`none` = off). Model, max tokens, temperature, Gemini's `thinking-level`. Search `pulseguard:` → `ai:` for our own settings. |
| `backend/ai/AiProperties.java` | Our settings as a Java record, validated at startup. |
| `backend/ai/AiConfig.java` | The thread pool the model calls run on (virtual threads). |
| `backend/ai/ModelCaller.java` | **The most important file.** Every AI call goes through it: gives up after a timeout, never throws, logs token counts but never the prompt. Learn this pattern. |

> Note: `ModelCaller` was pulled out of `IncidentSummaryService` during milestone 0.5, so it could
> be shared. This copy is the shared version.

### 2. Building the prompt
| File | Notice |
|---|---|
| `backend/ai/IncidentSummaryPrompt.java` | `SYSTEM` = the fixed rules. `facts()` = the data for this incident, wrapped in `<incident>…</incident>`. What's left out on purpose: the URL (can contain keys), headers, alert targets. |
| `backend/ai/PromptText.java` | Makes recorded text safe: one line, and `<` `>` swapped, so an error message can't close the tag and pretend to be the prompt. This is the defense against **prompt injection**. |

### 3. The feature itself
| File | Notice |
|---|---|
| `backend/ai/IncidentSummaryService.java` | 1) check the incident is yours, 2) reuse the saved summary if still fresh, 3) call the model, 4) save. `isFresh()`: resolved = written once; open = refreshed every 10 min. Not `@Transactional`, and the comment says why. |
| `backend/config/V19__incident_ai_summary.sql` | Two columns on `incidents` to cache the summary. |
| `backend/wiring/Incident.java` | Search `aiSummary`. |
| `backend/wiring/IncidentRepository.java` | Search `saveAiSummary`: a targeted `UPDATE`, so it can't overwrite changes the incident engine made meanwhile. |
| `backend/wiring/IncidentController.java` | Search `summary`. Returns **204 No Content** when there's no AI summary: that's the frontend's signal to fall back. |
| `backend/wiring/SecurityConfig.java` | Search `summary`. Session-only: an API key can't run up AI costs. |
| `backend/ai/IncidentSummaryResponse.java` | The JSON shape. |

### 4. Frontend
| File | Notice |
|---|---|
| `frontend/incidents.ts` | Search `getIncidentSummary`: a 204 becomes `null`. |
| `frontend/incidentSummary.ts` | The built-in, rule-based summary: free, instant, always works. The fallback. |
| `frontend/IncidentDetailPage.tsx` | Search `getIncidentSummary`. Tries AI, falls back on `null` **or** error. The **AI-generated** badge only shows on model text. |

### 5. Tests: how to test AI without paying for it
| File | Notice |
|---|---|
| `tests/IncidentSummaryPromptTest.java` | Tests the prompt text directly: URL never included, injection text fenced. No Spring, no model. |
| `tests/IncidentSummaryServiceTest.java` | A **mocked** model: provider failure, blank answer, timeout, caching, another user's incident never reaching the model. |
| `tests/IncidentSummaryApiIntegrationTest.java` | The real app with a **fake model bean** that counts calls: "3 page views → 1 model call". |
| `frontend/incidentPages.test.tsx`, `frontend/incidentSummary.test.ts` | Badge on AI text, fallback on 204 or error. |

---

## Key lessons

1. **AI failing must never break the page.** Every failure becomes "no summary" → fallback.
2. **Send only what's needed.** No URLs, secrets or targets in prompts.
3. **Data is not instructions.** Fence recorded text and say so in the rules.
4. **Cache.** Viewing a page 50 times costs one model call, not 50.
5. **Test with a fake model.** Tests are free, fast and don't need a key.

## Things we learned the hard way
- **Model names get retired.** `gemini-2.5-flash-lite` stopped working for new accounts (404).
  Check the list your key can use (the command is in `application.yaml`).
- **Gemini 3 thinks before answering**, and those hidden tokens count against the output cap, so
  the cap had to go up to 1024. `thinking-budget: 0` is rejected by some Gemini 3 models (3.5 Flash Lite gave a 400); `thinking-level` works on them.
- **Keys belong only in `.env`**, never `.env.example`. A real key was committed once to this
  public repo, and Google blocked it as leaked; a new key had to be made.

## Try it yourself (exercises)
1. Change the `SYSTEM` rules to ask for exactly 2 sentences. Restart and compare summaries.
2. In `IncidentSummaryPromptTest`, add a test that a monitor name with `<script>` is fenced.
3. Set `PULSEGUARD_AI_PROVIDER=none`, restart, open an incident: which code path shows the text?

# Milestone 3: Ask AI Answers From PulseGuard's Help Docs (RAG)

**What it does:** PulseGuard now has 16 help articles, readable by anyone on a public `/docs`
page. Ask AI answers "how do I…" questions from them: it searches the docs, answers only from
sections that really answer the question, and shows the sections it used as numbered
**Sources** under the answer. Citations in the text like "[1]" link to the same sections. When
the docs don't cover something ("Can I get SMS alerts?"), it says so instead of guessing.

**Why it exists:** before this, Ask AI knew your monitor data but not how PulseGuard itself
works, so "How do I set up Slack alerts?" got a guess from general knowledge.

`PLAN.md` is the plan it was built from. Part 1 explains the new ideas in 10 minutes. The Step 1,
4, 5 and 7 results record what real Gemini did, including the bug the golden set caught. These
files are snapshots from 1 Oct 2026; the real code lives in `src/` and `frontend/src/`.

---

## The one idea to understand first

**RAG = look it up first, then answer from what you found.** The model doesn't "know" our docs.
For each question we find the few sections that matter and hand them to the model with the
question, the same way Milestone 2 handed it monitor data:

```
"How do I get alerts in Slack?"
  → model: call search_help_docs("slack alerts setup")             (round 1)
  → our code: embed the words, find the nearest sections (pgvector)
              + sections containing the words (full-text), merge the two rankings
  → "[1] Slack alerts › Setting it up (/docs/slack-alerts#setting-it-up)\n<the section's text>
     [2] Getting started › Get alerted (…)\n…"
  → model writes the answer from those sections, citing [1], [2]    (round 2)
  → the panel links [1] and [2] to the sections on /docs
```

**Finding by meaning** works because of **embeddings**: a model turns text into a list of numbers
(here 768) so that texts that mean similar things get nearby lists. "notified in Slack" and
"alerts to a Slack channel" share few words but land close together. Embeddings blur exact
tokens like "502", so a word search runs alongside: that's **hybrid search**.

## The flow

```
On startup (HelpDocsIndexer)
  help/*.md → HelpArticles: one chunk per "##" section, with its article title
  → hash each chunk (text + model); embed only new or changed ones (gemini-embedding-2, 768)
  → help_chunks: text, embedding vector(768), tsvector for words      (V24)

On a question (Milestone 2's tool loop, one more tool)
  search_help_docs(query) → HelpDocsSearch: top 8 by cosine (above 0.60) + top 8 by words
  → reciprocal rank fusion → best 4 → numbered "[n] Title › Heading (/docs/…)" + text
  → GuardedToolCallback: 4,000-char cap for this tool, fence, record; sources read from the
    "[n] … (/docs/…)" lines → event: tool {label, sources} → saved in ai_tool_calls (V25)

In the browser
  /docs, /docs/:slug ← GET /api/help, /api/help/{slug} (public, no AI, no database)
  Ask AI answer → Markdown (model's links shown as text) + citations + numbered Sources
```

## Reading order

### 1. The docs themselves
| File | Notice |
|---|---|
| `help/slack-alerts.md` (then any other) | Front matter: `title`, `summary`, and `describes` (the code it explains, for whoever changes that code). Each `##` section **stands alone**, because each one becomes one search chunk: it repeats what it's about instead of saying "as above". |
| `help/plans-and-limits.md` | The plan table. Its numbers are checked against `PlanLimits` by `tests/HelpDocsFactsTest.java`, so the docs can't quietly go stale. |

### 2. Reading the docs into chunks
| File | Notice |
|---|---|
| `backend/help/HelpArticles.java` | `parse()`: front matter, intro, one `Section` per `##`. `anchor()`: the heading's id on the docs page ("can't" → "cant"); the frontend copies this rule (`frontend/helpAnchor.ts`). `Chunk.embeddingText()`: the section is embedded **with its titles** ("Slack alerts › Setting it up"), so "Setting it up" alone isn't ambiguous. |

### 3. Storing and embedding (pgvector)
| File | Notice |
|---|---|
| `backend/config/V24__help_chunks.sql` | `vector(768)` for meaning, a generated `tsvector` for words, an HNSW index with `vector_cosine_ops`. `embedding_model` per row: vectors from two models can't be compared. No `user_id`: the docs are the same for everyone, so no cross-tenant leak is possible. |
| `backend/config/docker-compose.yml`, `tests/TestDatabase.java` | The Postgres image became `pgvector/pgvector:pg16` everywhere, from one constant in tests. (PLAN.md Step 3: the switch also needed a `REINDEX`, because the two images sort text differently.) |
| `backend/help/HelpEmbeddings.java` | **Read this one slowly.** Every embedding request sets the model and dimensions explicitly and checks which model actually answered. Setting only some options made Spring AI silently use another model, and search quietly got worse; only the golden set noticed (PLAN.md Step 4). |
| `backend/help/HelpDocsIndexer.java` | `index()`: hash = model + text, so an unchanged section costs nothing on restart and a changed one is re-embedded alone. Advisory lock so only one instance indexes. Never stops the app. |
| `backend/config/application.yaml`, `backend/help/HelpDocsProperties.java`, `HelpDocsConfig.java` | Search `embedding` and `pulseguard.ai.help`: off unless `PULSEGUARD_AI_EMBEDDING_PROVIDER=google-genai`; the cut-off and candidate counts. |

### 4. Searching
| File | Notice |
|---|---|
| `backend/help/HelpDocsSearch.java` | Two SQL queries. Meaning: `embedding <=> ?::vector` (cosine distance), only rows of the current model, cut at `min-similarity`. Words: `plainto_tsquery` with `&` turned into `\|`, because requiring every word found nothing. Then **reciprocal rank fusion**: `1 / (60 + rank)` from each list, added up. |
| `tests/help-eval.yaml`, `tests/HelpDocsSearchEvalTest.java` | **The golden set**: 32 questions phrased like customers ask, each with the sections that answer it, and 6 the docs don't cover. Run on demand with real Gemini; prints hit@1, hit@4, MRR and every miss. Every search setting was changed only after running this. |

### 5. The tool and the prompt
| File | Notice |
|---|---|
| `backend/help/HelpDocsTools.java` | The `@Tool` description is a prompt: it says when to use the docs and when to use the data tools instead. Results are numbered `[n] Title › Heading (/docs/…)`, so the model can cite `[n]`. |
| `backend/ai/AskAiPrompt.java` | Search `search_help_docs`: answer only from sections that actually answer, cite `[n]`, otherwise say the docs don't cover it. The search returns *candidates*; the model decides (PLAN.md Step 4: a cut-off alone can't tell "not covered"). |
| `backend/chat/ChatService.java` | Search `helpDocs`: the tool is offered only while an embedding model is configured. |
| `backend/tools/GuardedToolCallback.java`, `ToolCallRecord.java`, `ToolLabels.java` | `MAX_HELP_RESULT_CHARS`: this tool may return 4,000 characters, the others 2,000. `ToolCallRecord.sourcesIn()` reads the sources back from the result **before** it is cut. The label says what was searched for. |
| `backend/chat/ChatTurn.java`, `ConversationService.java`, `AiToolCall.java`, `dto/ChatStreamEvents.java`, `dto/MessageResponse.java`, `backend/config/V25__ai_tool_call_sources.sql` | Sources travel with `event: tool`, are saved as JSON on the tool call, and come back merged (in order, repeats dropped) on each reopened answer. |

### 6. The docs page
| File | Notice |
|---|---|
| `backend/help/HelpController.java`, `dto/`, `backend/wiring/HelpArticleNotFoundException.java`, `GlobalExceptionHandler.java` | `GET /api/help` and `/api/help/{slug}` read the same `HelpArticles`: no database, no AI, so the docs work when both are down. Cached 5 minutes. |
| `backend/wiring/SecurityConfig.java` | Search `/api/help`: public like the status page, GET only. |
| `frontend/help.ts`, `docGroups.ts`, `DocsPage.tsx`, `DocArticlePage.tsx` | The list in groups (an unknown article goes under "More"), the article page, and the scroll to `#section` once the text is there. |
| `frontend/Markdown.tsx`, `frontend/helpAnchor.ts` | `react-markdown` + `remark-gfm` (tables). Raw HTML is never rendered. `anchors` gives each `##` the backend's id, so `/docs/slack-alerts#setting-it-up` lands on the section. |
| `frontend/App.tsx`, `RouteGuards.tsx`, `PublicDocsLayout.tsx`, `AppLayout.tsx`, `HeaderActions.tsx`, `events.ts`, `pages.ts` | `SignedInOrPublic`: the app's layout when signed in, a public frame otherwise. The docs and app pages share **one** layout route, so following a source doesn't remount it and close Ask AI (a bug found in the browser, PLAN.md Part B). Support → Documentation is a real link now. |

### 7. Sources and citations in the panel
| File | Notice |
|---|---|
| `frontend/ai.ts` | `HelpSource`, `onTool(label, sources)`, `sources` on saved messages. |
| `frontend/citations.ts` | `splitCitations()`: "[1]", "[1, 3]", "[2][3]"; "[1](…)" is a link, not a citation. `mergeSources()`: the same numbering the server saves. |
| `frontend/AskAiPanel.tsx` | Search `AnswerText`, `Citation`, `Sources`. Answers render as Markdown with `links={false}`: a link or image the model wrote shows as text, never followed or loaded. The only links are sources from the tool result, and only `/docs/` ones. A number with no source stays text. |
| `frontend/Markdown.tsx` | Search `remarkCitations`: a tiny plugin that turns citations in text nodes into `<cite>` elements, so they link inside lists and bold text but not inside code. |

### 8. Tests
| File | Notice |
|---|---|
| `tests/HelpDocsFactsTest.java` | The docs checked against the code: plan table, retention, incident thresholds, timeouts, Ask AI limits. Change the code and forget the docs, and this fails. |
| `tests/HelpArticlesTest.java` | Parsing, anchors (including "can't" and curly apostrophes), unique anchors in every real article. |
| `tests/HelpDocsIntegrationTest.java`, `FakeEmbeddingModel.java` | Real pgvector with a fake model that records what it embeds: `reEmbedsOnlyTheSectionThatChanged`, `switchingTheEmbeddingModelReEmbedsEverything`, `findsExactWordsThatMeaningAloneWouldMiss`, `ignoresVectorsFromAnotherEmbeddingModel`. |
| `tests/HelpDocsToolsTest.java`, `GuardedToolCallbackTest.java`, `ToolLabelsTest.java`, `ChatTurnTest.java` | The numbered result format, the larger cap that keeps its sources, the label. |
| `tests/ChatHelpDocsApiIntegrationTest.java` | The real endpoint with a tool-calling fake model: sources on the answer, "not covered" reaches the model, `docTextStaysInsideTheFence` (a section saying "ignore your instructions" stays data). |
| `tests/HelpApiIntegrationTest.java` | The docs API signed out, the 404, POST still closed. |
| `frontend/docs.test.tsx` | Reads the **real** articles and `help-eval.yaml`: every section the eval expects has the same id on the page, so the frontend and backend anchor rules can't drift. |
| `frontend/AskAiPanel.test.tsx`, `ai.test.ts` | Numbered sources live and reopened; `[1, 3]` → two links, `[9]` stays text; the model's links and images not followed; lists, code, tables. |

---

## Key lessons

1. **RAG is retrieval plus a rule.** Finding sections is half; the prompt must also say "answer
   only from these, and say so when they don't answer".
2. **Measure retrieval with a golden set, not by feel.** The first run scored 84% and looked
   fine; the numbers showed every hit came from the word search, which exposed the wrong-model
   bug. After the fix: hit@4 97%.
3. **A similarity cut-off can't detect "not covered".** Off-topic questions scored as high as
   real ones (0.73 for "SMS alerts" against Slack). Return candidates; let the model judge.
4. **Embeddings from different models don't mix.** Store the model per row, filter by it, and
   re-embed everything when it changes.
5. **Hybrid beats either alone.** Meaning finds paraphrases; words find "502" and exact names.
6. **Chunk for standalone reading.** One section per chunk, embedded with its titles.
7. **Only link what you control.** Sources come from our own tool result; anything the model
   writes as a link is shown as text.
8. **Docs drift; test them.** `HelpDocsFactsTest` turns the numbers in prose into assertions.

## Try it yourself (exercises)

1. Ask "How do I get alerts in Slack?", then open `ai_tool_calls` and read `result` and
   `sources` for that answer. Which sections came back, and in what order?
2. Run the eval (`PLAN.md` Step 4 shows the command). Then rerun with
   `-Dpulseguard.ai.help.text-candidates=0` (meaning only). Which questions does the word
   search rescue?
3. Edit one paragraph in `help/billing.md` and restart the backend. Read the log line
   "Help docs indexed: … embedded": how many sections were embedded, and why only that many?
4. Add a question to `help-eval.yaml` that the docs answer with a different wording from the
   heading, and one they don't cover at all. Run the eval and look at both.
5. Ask "Is Health failing right now, and what would its error mean?" (PLAN.md Step 7: it
   explained the error without searching the docs). Change one sentence in `AskAiPrompt` so it
   searches, and try again.
6. In `frontend/Markdown.tsx`, what would happen if `remarkCitations` also visited text inside
   `link` nodes? Write the test first.

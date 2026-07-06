# Analyze & Save a Pasted Contract (S-01) — Plan Brief

> Full plan: `context/changes/analyze-and-save-contract/plan.md`
> Research: `context/changes/analyze-and-save-contract/research.md`

## What & Why

S-01 is Falcon's north star and the PRD's Primary Success Criterion: a logged-in user pastes a contract + title, an LLM splits it into clauses and classifies each for risk (level/type + plain-Polish rationale) and generates negotiation points for the risky ones, and the result is saved owner-scoped and shown as an annotated breakdown with a "not legal advice" disclaimer. It proves the one belief the whole product rests on — that an LLM can reliably flag genuinely risky clauses a non-lawyer can act on.

## Starting Point

F-01 shipped the auth gate + isolation machinery (`SecurityUtils.currentUserId()`, auto-gated endpoints, the entity/migration/test patterns, and the frontend `apiFetch`/route-gating scaffold). The backend is otherwise domain-free beyond `users`. Research verified the load-bearing unknown against the real jars: Spring AI **2.0.0**'s structured output is `.call().entity(ClauseAnalysisResult.class)`, and it can be deterministically mocked with a one-line `@Bean @Primary ChatModel` lambda.

## Desired End State

A user visits `/analyses/new`, pastes a contract + title, sees a spinner + reassurance + indeterminate bar during the ~15s wait, then lands on `/analyses/{id}`: the disclaimer, each clause in document order with a color-coded risk badge + Polish risk-type + rationale, and each negotiation point inline under its clause. The analysis is persisted `ANALYZED` and owner-scoped (another user gets 404). Empty/over-limit input is rejected with readable messages; an LLM failure saves nothing. A deterministic MockMvc e2e (LLM mocked) proves the flow **and** cross-user isolation.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Spring AI call | `.call().entity(ClauseAnalysisResult.class)`, no `{format}` | The verified 2.0.0 API auto-injects the schema (reference doc is 1.1.x) | Research |
| Deterministic test | `@Bean @Primary ChatModel` lambda returning fixed JSON | Keeps the real converter + service in the test path; never calls OpenRouter | Research |
| Point→clause link | Match negotiation-point `clauseText` to a saved `Clause` at persist time (nullable `clauseId`) | Works with the reference schema unchanged; tolerates model imperfection | Plan |
| `Analysis.status` | Persist directly as `ANALYZED` | Synchronous flow — no dead intermediate `DRAFT` state | Plan |
| LLM failure | Atomic — controlled error (502), save nothing | No partial/garbage data; every saved analysis is complete | Plan |
| Input cap | `@Size(max=20000)` on `rawText` → 400 | Bounds cost/latency within gpt-4o context; readable reject | Plan |
| Test scope | Load-bearing e2e **+** cross-user isolation (user B → 404) | Closes F-01's deferred cross-user data-isolation gap in its natural home | Plan |
| Result layout | Clauses in document order, points inline per clause | Keeps each risk with its recommendation — the product's actionable value | Plan |
| Risk badge colors | Tailwind `red/amber/emerald` utilities | Zero theme-file churn; clear traffic-light semantics (theme has only `destructive` chromatic) | Plan |
| Progress UX | Spinner + copy + indeterminate bar | Honestly conveys "working, unknown duration" over 15s (NFR) | Plan |

## Scope

**In scope:** `Analysis`/`Clause`/`NegotiationPoint` entities + 4 enums + migration `002`; `ContractAnalysisService` (Spring AI 2.0 structured output) + mock test; `POST /api/analyses` + `GET /api/analyses/{id}` (owner-scoped) + orchestration + error mapping; the load-bearing e2e + cross-user isolation tests; `/analyses/new` + `/analyses/[id]` frontend with progress/disclaimer/error UX; 5 new shadcn components; `proxy.ts` matcher extension.

**Out of scope:** per-clause status UI/endpoint (S-02); history list (S-03); delete (S-04); `DRAFT`/`REVIEWED` transitions; FAILED records; PATCH/DELETE (so CORS methods unchanged); chunking, re-analysis, streaming, observability; live LLM in automated tests.

## Architecture / Approach

Vertical bottom-up build (mirroring F-01): **persistence → LLM service → analyze-and-save API → frontend**. The orchestration validates → calls `ContractAnalysisService.analyze()` → maps the transient records to owner-scoped entities (resolving `clauseId` by `clauseText` match) → persists `ANALYZED` → returns. Two `NegotiationPoint` types (transient record in an `analysis.llm` sub-package, persisted entity in `analysis`) resolve the name collision. Isolation reuses F-01's `findByIdAndOwnerId(id, currentUserId())` → 404 convention.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Persistence & migration | Entities + enums + `AnalysisRepository` + `002` migration | `ddl-auto=validate` strictness — align sized-varchar columns |
| 2. LLM analysis service | `ContractAnalysisService` (`.entity()`) + deterministic mock test | The `ChatModel` mock `getOptions()` NPE trap — use the lambda `@Bean` |
| 3. Analyze-and-save API | `POST`/`GET` + orchestration + the e2e + isolation tests | Persisting clauses before matching points to set `clauseId` |
| 4. Frontend analyze flow | `/analyses/new` + `/analyses/[id]` + progress/disclaimer/badges | The 15s wait UX + risk-color mapping (no theme tokens) |

**Prerequisites:** F-01 merged (done). Local Postgres up + `OPENROUTER_API_KEY` (dummy for tests; real for the browser walk).
**Estimated effort:** ~3–4 after-hours sessions across 4 phases; backend (1–3) is the bulk.

## Open Risks & Assumptions

- **Live model behavior is unverified** — research statically verified the API + mock; the first real OpenRouter/gpt-4o round-trip happens in Phase 2's optional manual check / Phase 4's browser walk. The prompt/schema may need tuning if the model's real JSON drifts from the schema (the `.entity()` path throws → controlled 502, so failures are graceful).
- **`clauseText` matching is fuzzy** — a reworded echo leaves `clauseId` null; the result view handles unlinked points in a trailing section, so this degrades gracefully rather than breaking.
- **`ddl-auto=validate` + long-text columns** — sized varchars aligned to `@Column(length=...)` avoid the `text`↔`varchar` validation ambiguity; Phase 1's context-load test catches any drift.

## Success Criteria (Summary)

- A logged-in user pastes a contract with a risky clause → a saved, owner-scoped breakdown with that clause flagged HIGH/MEDIUM and a linked negotiation point, shown with the disclaimer.
- Another user cannot reach it (404); empty/over-limit input and LLM failures produce readable, controlled outcomes.
- `cd backend && ./mvnw clean package` (incl. the deterministic e2e + isolation tests) and `cd frontend && pnpm build && pnpm lint` all green.

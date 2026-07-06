<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Analyze & Save a Pasted Contract (S-01)

- **Plan**: `context/changes/analyze-and-save-contract/plan.md`
- **Scope**: Full plan (Phases 1–4)
- **Date**: 2026-07-06
- **Verdict**: APPROVED
- **Findings**: 0 critical, 2 warnings, 4 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Findings

### F1 — LLM output length isn't validated before persist; overflow mis-routed to a nonsensical 409

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: `backend/src/main/java/com/morawski/dev/falcon/analysis/ContractAnalysisService.java:83-98` (`validateFieldsPresent`), interacting with the global `AuthExceptionHandler.java:30`
- **Detail**: `validateFieldsPresent` checks non-blank/non-null but never string *length*. `clause.text`/`rationale`/`recommendation` are capped at `varchar(10000)`/`varchar(2000)` by the migration, but nothing stops a verbose model response or a poorly-segmented giant "clause" from exceeding those bounds. The resulting `DataIntegrityViolationException` is uncaught by `AnalysisExceptionHandler` and falls through to F-01's *global* `AuthExceptionHandler` → `409 "Email already in use"` — the exact same class of bug already fixed once for blank/null fields, now recurring for length.
- **Fix**: Extend `validateFieldsPresent` to reject (`AnalysisFailedException`) any `text()`/`rationale()`/`recommendation()` exceeding the entity column lengths, mirroring the existing blank/null checks.
- **Decision**: FIXED — added length checks (`MAX_CLAUSE_TEXT_LENGTH`/`MAX_RATIONALE_LENGTH`/`MAX_RECOMMENDATION_LENGTH`) to `validateFieldsPresent`; added `ContractAnalysisServiceTest.oversizedClauseTextRaisesAnalysisFailedException` proving it.

### F2 — `matchClauseId`'s contains()-fallback can link a negotiation point to the wrong clause

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: `backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisService.java:63-78`
- **Detail**: Matching is exact-equals then first-match substring-containment. If two clauses have identical or overlapping text, the point silently attaches to whichever clause the loop reaches first — not necessarily the LLM's intended one. This was an explicitly-decided tradeoff during planning (research.md Open Question #2 → plan Round 1 Q1: "Match on clauseText at persist time... tolerate fuzzy matching"), not a fresh oversight.
- **Fix**: Acceptable stopgap for MVP given it was a deliberate, documented decision. Track for a follow-up slice — have the LLM emit a `clauseIndex` instead of `clauseText` for exact correlation instead of heuristic matching.
- **Decision**: ACCEPTED — deliberate, already-decided MVP tradeoff; not fixed now.

### F3 — `createAnalysis()` has no explicit transaction; final lazy-collection access is correct only incidentally

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Architecture
- **Location**: `backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisService.java:27-51`
- **Detail**: Unlike `getAnalysis()` (explicitly `@Transactional(readOnly=true)`), `createAnalysis()` relies on two independent short transactions (`saveAndFlush`, then `save`); `toResponse(saved)` accessing lazy collections afterward only avoids `LazyInitializationException` today because the second `save()` (entity already has an id → `merge()`, not `persist()`) happens to walk/initialize the cascaded collections. That's a Hibernate merge-behavior detail, not a guaranteed contract — a future refactor could silently reintroduce the exact bug `getAnalysis()` was fixed for.
- **Fix**: Not urgent. Wrap "build clauses → save → match points → save → map to response" in an explicit `@Transactional` helper, keeping only `contractAnalysisService.analyze()` outside it — closes both the atomicity gap and the lazy-load fragility in one change.
- **Decision**: FIXED — since a plain `@Transactional` on a self-invoked private method wouldn't apply (Spring's proxy-based AOP doesn't intercept `this`-calls), wrapped the persist+map step in a `TransactionTemplate` (constructed from an injected `PlatformTransactionManager`) instead. `createAnalysis` now: `analyze()` outside any transaction → `transactionTemplate.execute(status -> persistAndRespond(...))`. Full suite re-verified green.

### F4 — LLM transport/API failures aren't caught, only parse/schema failures

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: `backend/src/main/java/com/morawski/dev/falcon/analysis/ContractAnalysisService.java:62-81`
- **Detail**: `catch (IllegalStateException | JacksonException e)` is well-justified for schema-mismatch/parse failures, but doesn't cover transport-level failures (timeouts, 4xx/5xx from OpenRouter) — those would propagate as an uncontrolled 500 instead of the intended 502. None of the 4 test classes exercise a thrown transport exception.
- **Fix**: Widen the catch (or add a final broad catch) so any unexpected LLM-call failure funnels through `AnalysisFailedException` → 502.
- **Decision**: FIXED — widened `catch (IllegalStateException | JacksonException e)` to `catch (RuntimeException e)`, covering both original cases plus any transport/API failure from the model call. Added `ContractAnalysisServiceTest.transportFailureRaisesAnalysisFailedException` (a `ChatModel` lambda that throws directly) proving it.

### F5 — No logging around LLM failures

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: `AnalysisExceptionHandler.java`, `ContractAnalysisService.java`
- **Detail**: An `AnalysisFailedException`'s cause is never logged — a production LLM failure would be invisible. Consistent with the rest of the codebase (no `Logger` usage anywhere yet); observability is explicitly above-MVP per `CLAUDE.md`. Not a regression, just the app's one real external-dependency failure point.
- **Fix**: Not urgent — a one-line `log.error` would be cheap when observability is prioritized.
- **Decision**: SKIPPED — matches existing codebase convention (no logging anywhere yet); observability is an explicit above-MVP layer.

### F6 — `negotiation_points.clause_id → clauses.id` FK has no cascade (forward-looking note for S-04)

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architecture
- **Location**: `backend/src/main/resources/db/changelog/changes/002-create-analyses.yaml:129-134`
- **Detail**: `clauses.analysis_id`/`negotiation_points.analysis_id` both cascade `ON DELETE CASCADE`; `negotiation_points.clause_id → clauses.id` has no `onDelete` (defaults RESTRICT). Already known — `AnalysisFlowTest`'s cleanup explicitly nulls `clause_id` before deleting for this exact reason. Just flagging so it isn't lost before S-04 (delete) is planned.
- **Fix**: When S-04 is planned, either null `clause_id` before delete or change the FK to `ON DELETE SET NULL`.
- **Decision**: SKIPPED — not actionable now; S-04 isn't planned yet, revisit when that slice is designed.

## Supporting evidence

- **Plan drift check**: all 20 planned changes across all 4 phases MATCH their stated Contract exactly — zero DRIFT, zero MISSING, zero unplanned EXTRA files. The two mid-flight adaptations (broadening `ContractAnalysisService`'s catch clause to include `JacksonException`, and adding `@Transactional(readOnly=true)` to `getAnalysis()`) are both correctly reflected in the plan text itself, not silent deviations.
- **Security spot-checks (all clean)**: `ownerId` always comes from the authenticated principal, never client input; `GET /{id}` returns an identical 404 for "doesn't exist" and "exists but not yours" (no existence leak, confirmed by `AnalysisFlowTest.crossUserCannotReadAnotherUsersAnalysis`); `SecurityConfig` untouched, `/api/analyses/**` auto-gated by the existing `.anyRequest().authenticated()`; no hardcoded secrets; no XSS risk in the frontend (no `dangerouslySetInnerHTML`).
- **Success criteria**: fresh run at review time — backend 24/24 tests green (`./mvnw clean package`), frontend builds and lints clean. All manual criteria across 4 phases confirmed by the user during implementation; the two optional items (2.4 live-LLM sanity check, 3.5 curl walk) are explicitly documented as skipped-by-decision in Progress, not silently dropped.
- **The real production bug found during Phase 4 manual verification** (`LazyInitializationException` on `GET /api/analyses/{id}`, masked in tests by a test-classpath config-shadowing issue) is fixed, verified via a reproducing test, and the config gap that hid it from the test suite is closed.

<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Clause Decision Status (S-02 / FR-007)

- **Plan**: `context/changes/clause-decision-status/plan.md`
- **Mode**: Deep
- **Date**: 2026-07-09
- **Verdict**: REVISE → **SOUND** after triage (all 6 findings fixed)
- **Findings**: 1 critical, 3 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | FAIL |
| Plan Completeness | WARNING |

## Grounding

10/10 paths ✓ (2 new files correctly absent), 8/8 symbols ✓, Progress 3/3 phases and 18/18 items ✓, zero stray checkboxes in phase blocks, brief↔plan ✓.

Deep verification confirmed 8 of 9 riskiest claims: invalid enum → `400` (neither `@RestControllerAdvice` declares a broad handler); `AnalysisIsolationTest` already asserts body equality, not just status; `AuthBoundaryMatrixTest` has the POST pair to mirror and a GET-only `@MethodSource`; `apiFetch` uppercases the method before its non-GET check so `PATCH` gets the CSRF header; `getCsrf` is exported; `Button` spreads `...props` onto a native `<button>`; `decide`/`setUserDecision` have zero name collisions and no test asserts entity immutability; `ClauseResponse` is constructible directly from `Clause` getters.

One claim contradicted: `setAllowedMethods` really is `GET, POST` only, **and MockMvc bypasses `CorsFilter`** — no backend test in the original plan could have caught the omission.

## Findings

### F1 — The CORS fix is guarded only by a manual checkbox

- **Severity**: ❌ CRITICAL
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 1, change #6 + Success Criteria 1.1–1.5
- **Detail**: The plan names the CORS method list "the one silent failure mode," then verifies it with a manual step only. `MockMvc` sends no `Origin` header, so `CorsFilter` passes every test request through regardless of `setAllowedMethods`. If change #6 is skipped, all automated criteria and CI go green and the bug appears only in a browser as an opaque preflight rejection. The plan overlooked that `AuthBoundaryMatrixTest.corsPreflightIsPermitted` (`:65`) already issues an `OPTIONS` request and could be extended.
- **Fix**: Extend `corsPreflightIsPermitted` (or add a sibling) to assert an `OPTIONS` preflight with `Access-Control-Request-Method: PATCH` returns `PATCH` in `Access-Control-Allow-Methods`; add it to Phase 1's automated criteria.
- **Decision**: FIXED — added as Phase 1 change #9 (third test) and criterion 1.4; the old manual preflight criterion was replaced by a real-browser check.

### F2 — Happy-path test can't distinguish "persisted" from "mutated in memory"

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Critical Implementation Details; Phase 1, change #9 (now #10)
- **Detail**: (a) The plan justified `@Transactional` with `LazyInitializationException`, but `decide()` already materializes the clause — nothing lazy is touched. The real reason is the dirty-check flush. (b) Consequently the failure mode is silent: without `@Transactional` the entity is detached, the mutation never flushes, and the endpoint still returns `200` with the new value read off the in-memory object. The flow test asserted only the `PATCH` response body, so it would pass. The sole test that would have caught it was a bullet buried in the isolation test.
- **Fix**: Move the `PATCH` → `GET` round-trip assertion into the flow test where persistence belongs; correct the Critical Implementation Details paragraph to name dirty-check flush as the reason and silent non-persistence as the failure mode; leave the isolation test focused on cross-user refusal.
- **Decision**: FIXED — Critical Implementation Details rewritten; round-trip moved into change #10; isolation test (#8) narrowed.

### F3 — Playwright strict mode will break on N clauses sharing one button name

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 2 change #2 + Phase 3 change #1
- **Detail**: Phase 2 gives each decision button the Polish label as its accessible name; Phase 3 located it with `getByRole("button", { name })`. Every clause card renders the same three names, so on a 3-clause contract the locator resolves 9 elements and strict mode throws. The clause text can't disambiguate — it is LLM-generated and rendered as a bare `<p>` (`page.tsx:97`).
- **Fix A ⭐ Recommended**: Wrap each clause's buttons in `<div role="group" aria-label="Decyzja: klauzula {n}">`.
  - Strength: gives the spec a stable indexable scope and delivers the screen-reader semantics criterion 2.8 already promises.
  - Tradeoff: introduces a positional, non-semantic index label.
  - Confidence: HIGH — `Button` spreads `...props` onto a native `<button>` (`button.tsx:49,62`), so roles and ARIA pass through.
  - Blind spot: haven't confirmed the group label reads naturally in Polish.
- **Fix B**: Scope the spec to the first clause card with `.first()`.
  - Strength: zero production-code change.
  - Tradeoff: hides a real a11y gap; three same-named buttons per card stay indistinguishable to a screen-reader user.
  - Confidence: HIGH — `.first()` is standard Playwright.
  - Blind spot: None significant.
- **Decision**: FIXED via Fix A — group added in Phase 2; Phase 3 locator scoped through it.

### F4 — Testing Strategy promises a unit test no phase creates

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Testing Strategy, bullet 1
- **Detail**: "Aggregate behaviour: `Analysis.decide(...)` sets the right clause and throws for an id outside the aggregate" — but Phase 1's changes added only MVC-level tests. No file named, no criterion running it.
- **Fix**: Name it — a plain JUnit test (no Spring context) exercising `decide()` on a hand-built Analysis, as a Phase 1 change entry with its own automated criterion.
- **Decision**: FIXED — `AnalysisDecideTest` added as Phase 1 change #7, criterion 1.2.

### F5 — Service contract carries a placeholder instead of a named mapper

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 1, change #4 (code snippet)
- **Detail**: The snippet ended `return /* the updated clause, mapped as ClauseResponse */;`. No single-clause mapper exists — the mapping is an inline lambda inside the private `toResponse` (`AnalysisService.java:97-100`).
- **Fix**: Extract the lambda into `private static ClauseResponse toClauseResponse(Clause c)` and call it from both `toResponse` and the new method.
- **Decision**: FIXED — extraction named in change #4; `decide(...)` now returns the mutated `Clause` (change #2) so the service maps it without re-finding it.

### F6 — Phase 3's automated criteria need a live LLM and an API key

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 3, Success Criteria 3.1–3.2
- **Detail**: Filed under "Automated Verification" but require a running stack and `OPENROUTER_API_KEY`, and act on a non-deterministic clause count. The plan said "local-only gate" in prose; the criteria didn't reflect it, sitting awkwardly against the PRD's test-determinism guardrail.
- **Fix**: Relabel 3.1–3.2 as manual/local criteria, and state in the spec's contract that it must tolerate a variable clause count.
- **Decision**: FIXED — Phase 3 now has no Automated subsection; all three criteria are Manual with a note explaining why.

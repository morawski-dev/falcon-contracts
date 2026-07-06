<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Classification Pipeline + Isolation — Test Hardening

- **Plan**: context/changes/testing-classification-pipeline-isolation/plan.md
- **Scope**: Full plan (Phases 1-3 of 3)
- **Date**: 2026-07-07
- **Verdict**: APPROVED
- **Findings**: 0 critical, 1 warning, 3 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

## Findings

### F1 — Index-based clause assertions rely on unguaranteed query order

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality (reliability)
- **Location**: `backend/src/test/java/com/morawski/dev/falcon/analysis/ClassificationContractTest.java:146` (and the parallel POST-response assertions earlier in the same method)
- **Detail**: The GET-path (and POST-response) assertions index into `clauses.get(0)/.get(1)/.get(2)` expecting HIGH/MEDIUM/LOW order. `Analysis.clauses` (`Analysis.java:42-43`) has no `@OrderBy`, and `AnalysisRepository.findByIdAndOwnerId` issues a plain unordered SELECT. The reviewing agent confirmed this passes today only because Postgres happens to return heap-scan rows in insertion order for a freshly-inserted small table — not a guaranteed contract. This is a latent flaky-test risk, not a currently-observed failure.
- **Fix A ⭐ Recommended**: Match clauses by content (riskType or text) instead of index inside the test.
  - Strength: Contained entirely to test code — respects the plan's explicit "No new production code" scope boundary for this phase.
  - Tradeoff: A few more lines of lookup logic in the assertion helper.
  - Confidence: HIGH — this is a standard fix for order-dependent test assertions and doesn't touch anything outside `ClassificationContractTest.java`.
  - Blind spot: None significant.
- **Fix B**: Add `@OrderBy("id")` to `Analysis.clauses` in production code.
  - Strength: Makes ordering an actual guaranteed contract, which may also matter for real API consumers displaying clauses in a stable order.
  - Tradeoff: Touches production code — directly conflicts with this plan's "What We're NOT Doing: No new production code" boundary. Would need to be its own follow-up change.
  - Confidence: MEDIUM — reasonable fix, but out of scope for this test-hardening phase.
  - Blind spot: Haven't checked whether the frontend or any other consumer already assumes/depends on clause ordering.
- **Decision**: FIXED via Fix A — `assertClassificationContract` now looks up clauses by `riskType` via a `findClauseByRiskType` helper instead of indexing into the array; re-ran `ClassificationContractTest`, still passes.

### F2 — Byte-identical body assertion in the isolation test is currently tautological

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (assertion strength)
- **Location**: `backend/src/test/java/com/morawski/dev/falcon/analysis/AnalysisIsolationTest.java:74`
- **Detail**: The reviewing agent instrumented the test and confirmed both the cross-user and missing-id response bodies are literally `""` under MockMvc — `ResponseStatusException`'s error path never routes through `BasicErrorController` inside MockMvc, regardless of whether ownership is correctly enforced. So today the body-equality assertion adds no coverage beyond the status-code equality on the line above it. It isn't wrong (and would catch a *future* regression that started attaching a distinguishing error body), just weaker than it reads.
- **Fix**: Add a one-line comment noting that the body-equality assertion is defense-in-depth against a future distinguishing error body, since both are empty today — so a future reader doesn't mistake it for meaningfully exercised coverage.
- **Decision**: FIXED — added the clarifying comment above the body-equality assertion.

### F3 — AnalysisIsolationTest uses the repository-test password convention instead of the MockMvc-test convention

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: `backend/src/test/java/com/morawski/dev/falcon/analysis/AnalysisIsolationTest.java:52`
- **Detail**: Every other MockMvc-based test in this package (`AnalysisFlowTest`, `ClassificationContractTest`, `ConverterRobustnessTest`) persists users via a real `PasswordEncoder`; only the pure-repository test (`AnalysisRepositoryTest`) uses a literal `"hashed-password"` string. `AnalysisIsolationTest` is MockMvc-based but uses the repository-test's literal convention. Not a functional bug — `.with(user(...))` bypasses password checks either way — but it breaks the established convention split, and this file is now cited in `test-plan.md` §6.4 as a reference test, so a future test-writer copying it may not understand why it deviates.
- **Fix**: Switch to the `PasswordEncoder`-based `persistUser` helper used by the other MockMvc tests in the package, purely for convention consistency.
- **Decision**: FIXED — `persistUser` now uses the autowired `PasswordEncoder`, matching `AnalysisFlowTest`/`ClassificationContractTest`/`ConverterRobustnessTest`; re-ran `AnalysisIsolationTest`, still passes.

### F4 — Unplanned mvnw permission-bit change swept into the Phase 1 commit

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: `backend/mvnw` (commit `b855927`)
- **Detail**: `backend/mvnw`'s file mode changed 100644 → 100755 (making the Maven wrapper executable) inside the Phase 1 commit, even though only four files were explicitly `git add`ed. It was already staged in the index before the implementation session began, so `git add <specific paths>` didn't exclude it. This was surfaced and disclosed to the user transparently at the time it happened; it's a harmless, likely-intentional fix unrelated to this plan's content.
- **Fix**: None needed — already disclosed, harmless, and predates this phase's own changes. Noted here only for the review's audit trail.
- **Decision**: SKIPPED — no action needed.

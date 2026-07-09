<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Clause Decision Status (S-02 / FR-007)

- **Plan**: context/changes/clause-decision-status/plan.md
- **Scope**: Phase 3 of 3 (full plan)
- **Date**: 2026-07-09
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Grounding

All 13 planned changes verified MATCH against actual code (file:line evidence per item, via two parallel sub-agents). The one intentional mid-implementation deviation — the content-stable `aria-label` (embedding `RISK_TYPE_LABEL[clause.riskType]`) and the e2e locator swap from `.first()` to a risk-type regex, both fixing a discovered `Analysis.clauses` ordering instability (no `@OrderBy`) — was verified correctly implemented, and correctly left the frozen Phase 2/3 plan text untouched, per this project's convention that only the Progress section is mutable during implementation. Zero scope creep: every changed file traces to a planned item, the deviation, or expected `context/changes/` scaffolding (change.md, plan-brief.md, plan.md, research.md, reviews/plan-review.md).

Success criteria re-verified fresh (not just trusted from Progress checkmarks): `./mvnw clean package` → 54/54 tests, 0 failures, BUILD SUCCESS; `pnpm lint` → clean; `pnpm build` → compiles, type-checks, all 8 routes generated. Manual criteria (Phase 1 1.6-1.7, Phase 2 2.3-2.8, Phase 3 3.1-3.3) all carry explicit user confirmation at each phase gate, with 3.1/3.2 additionally backed by directly-observed tool output (Playwright pass results) presented to the user before confirmation — no rubber-stamping detected.

## Findings

### F1 — Analysis.decide() doesn't null-guard clause.getId()

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: backend/src/main/java/com/morawski/dev/falcon/analysis/Analysis.java:93-98
- **Detail**: `decide(...)` calls `clause.getId().equals(clauseId)` in its search loop. Not currently reachable as a bug — the only production call path (Controller → Service.updateClauseDecision → findByIdAndOwnerId → decide) always loads a persisted Analysis, so every Clause already has a non-null id. But a future caller invoking `decide()` on a freshly-constructed, unsaved Analysis (ids still null before the first flush) would NPE on the first comparison instead of getting a clean "not found."
- **Fix**: Flip the comparison to `clauseId.equals(clause.getId())` (clauseId is always the method's non-null parameter), or use `Objects.equals(clause.getId(), clauseId)`.
- **Decision**: FIXED — flipped to `clauseId.equals(clause.getId())`; `AnalysisDecideTest`/`AnalysisIsolationTest`/`AnalysisFlowTest` re-verified green (13/13).

### F2 — E2E clause locator has an undocumented collision edge case

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: frontend/e2e/clause-decision.spec.ts:47-50
- **Detail**: The mid-Phase-3 fix (commit 3896d85) locates the target clause via `getByRole("group", { name: /Automatyczne przedłużenie/ })` — the clause's risk type — to work around `Analysis.clauses` having no `@OrderBy`. This correctly solves the stated instability for the current fixed CONTRACT_TEXT (exactly one AUTO_RENEWAL clause), but the header comment doesn't note that two clauses sharing the same risk type would make the locator match multiple groups and trip Playwright's strict mode. A future editor extending CONTRACT_TEXT with a second auto-renewal-shaped clause would hit a confusing failure with no signpost back to this constraint.
- **Fix**: Add one line to the header comment noting the locator assumes CONTRACT_TEXT produces a unique risk type per clause.
- **Decision**: FIXED — added a "Known limitation" comment noting the assumption.

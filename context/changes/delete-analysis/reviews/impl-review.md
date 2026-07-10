<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Delete an analysis (S-04)

- **Plan**: context/changes/delete-analysis/plan.md
- **Scope**: Full plan (Phases 1–4 of 4)
- **Date**: 2026-07-10
- **Verdict**: APPROVED
- **Findings**: 0 critical, 1 warning, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

## Evidence

- Git diff `d76bc71..HEAD` matches the plan's file list exactly — 17 code/test files, all planned, none extra.
- Fresh `./mvnw clean package` (re-run during review): BUILD SUCCESS, 59/59 tests pass.
- Fresh `pnpm lint`: clean. Fresh `pnpm build`: BUILD SUCCESS, 7 routes, no new TS errors.
- `pnpm test:e2e` (run live during Phase 4, same code, no changes since): 3/3 passed.
- Plan-drift sub-agent: every numbered Change Required in all 4 phases verified MATCH against actual file content, including the byte-pinned `deleteOwned` snippet (`AnalysisRepository.java:27-29`) — zero DRIFT, MISSING, or EXTRA.
- Safety/pattern sub-agent: owner-scoping invariant intact (no bare `findById`/`deleteById` introduced), DB-cascade correctness verified against `002-create-analyses.yaml` and the new `004` changeset, `role="alertdialog"` confirmed by reading `@radix-ui/react-alert-dialog`'s source directly (validates the e2e spec's locator strategy).

## Findings

### F1 — Delete buttons share an identical accessible name across rows

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: frontend/src/app/dashboard/page.tsx:145-147
- **Detail**: Every row's delete-trigger button renders as plain text "Usuń" with no distinguishing accessible name. With more than one analysis, a screen-reader user tabbing through hears "Usuń, button" identically for every row. The codebase already solved this exact ambiguity class in the detail page's clause-decision groups (`aria-label={\`Decyzja: klauzula ${index + 1} (${RISK_TYPE_LABEL[...]})\`}`, `analyses/[id]/page.tsx:160`), a pattern S-02's impl-review called load-bearing. Not a plan violation (the contract only specified button text), and not caught by manual testing (single-row account).
- **Fix**: Add `aria-label={\`Usuń analizę: ${analysis.title}\`}` to the trigger `Button`, matching the codebase's established content-embedded-label convention.
- **Decision**: FIXED

### F2 — Comment's stated justification is now stale (self-contradicted by this PR's own migration)

- **Severity**: 💭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisRepository.java:25-26
- **Detail**: The comment guarding `deleteOwned` says entity cascade "can violate fk_negotiation_points_clause." True when written; false after changeset 004 (this same PR, Phase 1) made that FK `ON DELETE SET NULL` — Postgres now auto-nulls the reference instead of raising a violation, regardless of delete order. The recommendation to stay on the bulk path is still correct for other reasons (avoids loading the aggregate, single statement); only the cited reason is stale.
- **Fix**: Reword the comment's last sentence to cite the reasons that still hold, not the FK violation changeset 004 already neutralizes.
- **Decision**: FIXED

### F3 — Pre-existing teardown comment is now factually incorrect

- **Severity**: 💭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: backend/src/test/java/com/morawski/dev/falcon/analysis/AnalysisFlowTest.java:104
- **Detail**: The pre-existing (S-01) two-phase `@AfterEach` teardown comment states the FK "has no cascade, by design." Literally false as of changeset 004 in this PR. The plan's "What We're NOT Doing" deliberately declined to simplify this teardown's *code* (now merely redundant, still passes) but didn't anticipate the migration would make the accompanying *comment* wrong — a smaller, separable fix than the code simplification the plan correctly scoped out.
- **Fix**: Update the comment to state the FK now cascades via SET NULL, making the teardown redundant rather than required, without touching the teardown code.
- **Decision**: FIXED

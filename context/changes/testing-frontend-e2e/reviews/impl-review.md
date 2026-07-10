<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Deterministic browser E2E + disclaimer guardrail coverage

- **Plan**: context/changes/testing-frontend-e2e/plan.md
- **Scope**: Full plan (Phases 1–5)
- **Date**: 2026-07-10
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 2 warnings, 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Method

Two parallel sub-agents reviewed all 20 files changed across commits `e110b0d^..9d2f69f` (the full change): one checked each of the 15 planned "Changes Required" items against actual file contents (byte-level diffs where relevant), the other scanned for security/reliability/pattern issues and cross-checked against sibling files. A final pass re-ran `pnpm lint`, `tsc --noEmit`, and YAML validation — all clean. Backend suite (59/59) and the e2e suite (2 real GitHub Actions runs, `29102214919` and `29102730314`) were already verified live during Phases 1–4; not re-run here.

## Findings

### F1 — `disclaimer.spec.ts` creates an analysis with no cleanup

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: `frontend/e2e/disclaimer.spec.ts:22-28`
- **Detail**: The spec registers a user, seeds a real analysis via `seedAnalysis`, and asserts the disclaimer — but never deletes what it created. Every sibling spec that creates an analysis (`analysis-history`, `clause-decision`, `delete-analysis`, `analysis-result`) ends with the same delete-and-confirm cleanup block. This is also a direct instance of CLAUDE.md's own E2E hard rule: "Each test runs standalone — its own setup, action, assertion, and **cleanup**." Not present in the plan's contract for this spec either — a genuine oversight, not a documented exception. Impact is orphaned `Analysis`/`User` rows accumulating in the `e2e`-profile database across repeated runs — not correctness-breaking, but a real drift from an established pattern in this exact changeset.
- **Fix**: Add the same cleanup block used in `analysis-history.spec.ts` (goto `/dashboard`, locate the row by title regex, click "Usuń", confirm in the `alertdialog`, assert the row is gone).
- **Decision**: FIXED — cleanup block added to `disclaimer.spec.ts`, verified lint + tsc clean.

### F2 — test-plan.md §3 Phase 3's Change-folder doesn't point at an archive path

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: `context/foundation/test-plan.md:97`
- **Detail**: Phase 5's plan contract said "§3 Phase 3 Status → complete, Change folder → the archive path." Status is correctly `complete`, but the Change-folder cell still reads `context/changes/testing-frontend-e2e/` — because this change has not actually been archived yet (`/10x-archive` is a separate, later step in this project's workflow; confirmed no archive commit exists for this change, unlike sibling changes which each got a dedicated `chore(archive): ...` commit). Writing a fabricated archive path would have been worse — it would point at a directory that doesn't exist. This was a deliberate call made during Phase 5, not an oversight, but the plan's literal contract is technically unfulfilled until archiving happens.
- **Fix**: No text edit needed now — the current path is accurate. Run `/10x-archive testing-frontend-e2e` when ready to close out the change folder; that step naturally makes this cell correct without another manual edit.
- **Decision**: ACCEPTED — deferred to `/10x-archive testing-frontend-e2e`, not a text fix.

### F3 — `application-e2e.properties` ships in the production jar (plan-level choice, not implementation drift)

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architecture
- **Location**: `backend/src/main/resources/application-e2e.properties`
- **Detail**: The plan explicitly specified `src/main/resources` for this file (Phase 1, item 3), and the implementation matches the plan exactly — this is not a drift finding. But it's worth surfacing: `E2eChatModelConfig.java` and `ClauseAnalysisFixtures.java` were deliberately kept under `src/test/java` specifically so they never ship in the jar (reasoned through at length in Phase 1). `application-e2e.properties` doesn't get the same treatment — it lives in `src/main/resources`, so `mvn clean package` bundles it. Actual risk is low: even if `SPRING_PROFILES_ACTIVE=e2e` were mistakenly set on a real deployment, there's no stub `ChatModel` bean outside the test classpath to activate, so the worst case is a broken OpenRouter call using the dummy key — a fail-safe, not a bypass. Moving it to `src/test/resources` would still work (Maven copies test resources into `target/test-classes`, which is already on the runtime classpath via `additional-classpath-elements`) and would make the "test-only surface" story consistent across both the Java stub and its config.
- **Fix**: Move `application-e2e.properties` from `backend/src/main/resources/` to `backend/src/test/resources/`.
- **Decision**: FIXED — moved. Verified: `mvn test` (9/9) still passes, and a live boot on an alternate port (no `OPENROUTER_API_KEY` set) still returns 201 with the exact `MULTI_CLAUSE_JSON` fixture.

## What held up well

- 14 of 15 planned "Changes Required" items are exact byte-level or line-level matches — including the trickiest ones (the `useTestClasspath`→`additional-classpath-elements` correction propagated consistently across `playwright.config.ts`, `E2eChatModelConfig.java`'s javadoc, `test-plan.md` §6.6, and the per-rollout-phase note).
- No unplanned files in the diff beyond the one explicitly-approved addendum (`data-testid` on `page.tsx`, confirmed as a single-line additive change).
- `page.route()` handlers in `analysis-input-validation.spec.ts` correctly `route.continue()` on non-matching methods — the GET-swallowing risk the plan flagged never materialized.
- The `analysis-result.spec.ts` testid locator is properly scoped and cannot match ambiguously given the fixture's one-risk-type-per-clause design.
- No secrets, no `OPENROUTER_API_KEY` reference anywhere in `ci.yml`, CI-only env vars correctly gated behind `isCI` in `playwright.config.ts`.
- New backend/CI code follows existing sibling patterns exactly (lambda-based `@Bean @Primary ChatModel`, `e2e` job's checkout→setup→cache ordering matching `backend`/`frontend` jobs).

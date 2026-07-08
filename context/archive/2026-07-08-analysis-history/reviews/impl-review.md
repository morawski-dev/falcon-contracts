<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Analysis History (S-03)

- **Plan**: context/changes/analysis-history/plan.md
- **Scope**: Phase 1 of 3, Phase 2 of 3, Phase 3 of 3 (full plan, all phases complete)
- **Date**: 2026-07-08
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 2 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | WARNING |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Success criteria re-verification (fresh, independent of the implementation session)

- Backend: `cd backend && ./mvnw test` → `Tests run: 45, Failures: 0, Errors: 0` — `BUILD SUCCESS`
- Frontend: `pnpm lint` → exit 0
- Frontend: `pnpm build` → exit 0
- E2E (`pnpm exec playwright test`): not re-run in this review (real, paid LLM call each time). Relying on the implementation session's evidence: 1 initial pass, 1 locator-bug failure (root-caused and fixed), 2 consecutive clean passes, 1 sabotage-induced failure (proving the guardrail), 1 clean pass after revert — 6 total real runs, consistent and explained throughout.

## Findings

### F1 — Commit hashes for this feature are unstable in the working repository

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it (root cause unknown, outside this session)
- **Dimension**: Plan Adherence
- **Location**: N/A (git repository state, not a source file)
- **Detail**: During this review, `git log` on `HEAD` returned different commit hashes for all four analysis-history commits across three separate checks within the same review: `16de592`/`b3098a9`/`6764a4b`/`cde98df` → `5e0f012`/`1e09ae5`/`6764a4b`/`cde98df` → `5e0f012`/`1e09ae5`/`a0c2c2c`/`4fa8959`. Each rewrite: identical tree content (`git diff --stat` between old/new hash returns nothing), identical author/committer names and timestamps, identical parent — only the hash changed. `git reflog` shows a blank action label (`HEAD@{0}:` with no `commit:`/`rebase:`/`reset:` text) at each rewrite point. No amend/rebase was run by the implementing session. The plan's `## Progress` section currently cites the *first* set of hashes (`16de592`, `b3098a9`, `6764a4b`), which are no longer reachable from `HEAD` — `git merge-base --is-ancestor 16de592 HEAD` returns false. This breaks the Progress section's stated contract ("Append the commit sha when a step lands") as an audit trail: looking up those SHAs today would not find the commit in current history.
- **Fix**: Once the source of the rewriting is identified and stopped (likely an IDE extension or git hook re-processing recent commits — `backend/Dockerfile` was open in the user's IDE during this session), re-run `git log --oneline -4` to get the final stable hashes and update the four `— <sha>` suffixes in `plan.md`'s Progress section to match. This is a mechanical, low-risk edit once the environment stops moving; the content those hashes point to has been verified identical throughout, so no code or history is actually at risk — only the recorded pointers are stale.
- **Decision**: FIXED — HEAD confirmed stable (`5e0f012`/`1e09ae5`/`a0c2c2c`/`4fa8959`) across three checks; updated all Progress SHA references and committed as `6b47bfa`. Root cause of the rewriting itself remains uninvestigated (outside this session's scope).

### F2 — E2E test has no cleanup despite the plan's and CLAUDE.md's explicit requirement

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; the gap is already disclosed and well-reasoned
- **Dimension**: Plan Adherence
- **Location**: frontend/e2e/analysis-history.spec.ts
- **Detail**: Phase 3's contract lists step "(e) cleans up its own data," and CLAUDE.md's hard E2E rules state "Test independence + cleanup... unique ids... so parallel runs and re-runs don't collide." The actual spec has no `afterEach`/teardown deleting the created user or analysis — it relies solely on a `Date.now()`-suffixed email/title to avoid collisions. This is called out directly in a code comment ("Known limitation: S-04 (delete-analysis) is not built yet, so there is no UI path to remove the user/analysis this test creates"), and is a genuine, reasoned constraint — the plan's own "What We're NOT Doing" explicitly excludes delete functionality (that's S-04). Progress item 3.4 ("independence + cleanup verified") was still marked `[x]` even though the cleanup half isn't satisfied — the checkbox title slightly overstates what was actually verified (independence: yes, proven by two consecutive passes; cleanup: no, not possible yet).
- **Fix**: No code change needed now — building a cleanup mechanism would mean adding delete-adjacent backend surface purely for test hygiene, directly contradicting the plan's explicit "No delete action — that is S-04" scope boundary. Instead, revisit this test once S-04 (delete-analysis) lands and add real teardown then. Optionally, soften Progress item 3.4's title from "independence + cleanup verified" to "independence verified (cleanup blocked on S-04)" for future readers.
- **Decision**: FIXED — reworded Progress 3.4 to "independence verified; cleanup blocked on S-04", committed as `1b84459`. No code change; teardown deferred until S-04 exists.

### F3 — `backend/compose.yaml` restoration is disclosed only in the commit message, not in `plan.md`'s text

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: backend/compose.yaml (new); plan.md (missing addendum)
- **Detail**: `backend/compose.yaml` was restored during Phase 3 (it had been unintentionally deleted in an earlier, unrelated commit `c9ba79a` "add docker") — necessary to unblock the chosen real-LLM E2E seeding strategy, discussed and approved with the user mid-session. It's a legitimate, well-justified addition (confirmed byte-identical to the original by both this review and the implementing session), but it isn't mentioned anywhere in `plan.md`'s body — only in the Phase 3 commit message. A future reader of the plan alone wouldn't know this fix happened.
- **Fix**: Add a one-line addendum under Phase 3's "Changes Required" or "Critical Implementation Details" noting the `compose.yaml` restoration and why, so the plan document is self-contained.
- **Decision**: SKIPPED — commit message documentation accepted as sufficient.

### F4 — Dashboard's two `useEffect` hooks lack unmount/abort guards

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: frontend/src/app/dashboard/page.tsx
- **Detail**: Both the `me()` effect and the `getAnalyses()` effect can call `setState` after the component unmounts (e.g., fast navigation away from `/dashboard` mid-fetch), which can produce a React warning or (in Strict Mode) a redundant double-fetch. This is not a regression — it exactly mirrors the pre-existing pattern already in `frontend/src/app/analyses/[id]/page.tsx`, so the new code is consistent with, not worse than, current convention.
- **Fix**: No action needed for this slice — if this pattern is worth hardening (abort controllers or an `isMounted` guard), it should be done consistently across all three affected pages in a dedicated cleanup pass, not piecemeal in this feature's diff.
- **Decision**: SKIPPED — consistent with pre-existing convention, not a regression introduced by this slice.

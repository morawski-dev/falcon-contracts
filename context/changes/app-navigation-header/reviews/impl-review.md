<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: App Navigation Header (S-05)

- **Plan**: `context/changes/app-navigation-header/plan.md`
- **Scope**: Phase 4 of 4 (full plan)
- **Date**: 2026-07-13
- **Verdict**: APPROVED
- **Findings**: 0 critical, 2 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | WARNING |

## Grounding

Two sub-agents independently verified all 11 planned changes (routes moved, `(app)/layout.tsx`, `AppHeader`, `globals.css`, dashboard chrome removal, root redirect, metadata, deleted SVGs, `app-header.spec.ts`'s 5 tests, `.first()` removal) as **MATCH** — no DRIFT, no MISSING items. All grep-based automated criteria (1.4/1.5, 2.4, 3.4/3.5, 4.2–4.5) re-verified clean. `pnpm lint` and `pnpm build` both clean. `pnpm test:e2e`: 12/13 — the one failure is `analysis-result.spec.ts`, a pre-existing, extensively diagnosed local-environment issue (documented in `change.md`, reproduces identically at clean HEAD, passed 13/13 when the environment was correct during Phase 4's own verification).

## Findings

### F1 — Logout has no error handling

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: `frontend/src/components/app-header.tsx:11-14`
- **Detail**: `handleLogout` awaits `logout()` with no `try`/`catch`. If the POST rejects (session already gone, network blip, or a fast double-click racing two logout calls), the rejection is unhandled and `router.push("/login")` never fires — the user is left on a stuck page with no error and no way out except a manual reload. This logic is copy-pasted unchanged from the old dashboard `handleLogout` (verified via `git show 1639969` — it isn't a new regression), but it is now the app's **only** logout affordance, so the blast radius of the existing gap is larger than before.
- **Fix A ⭐ Recommended**: Navigate to `/login` unconditionally, regardless of whether the server call succeeded.
  ```ts
  async function handleLogout() {
    try {
      await logout();
    } finally {
      router.push("/login");
    }
  }
  ```
  - Strength: Guarantees the user always reaches `/login` — the one thing they actually want from clicking "Wyloguj" — even if the backend call fails; matches the app's existing bias toward client-side redirect over strict server-confirmation.
  - Tradeoff: A failed server-side session invalidation is silently swallowed; the `JSESSIONID` cookie may outlive the intent to log out until it naturally expires.
  - Confidence: HIGH — `finally` is a minimal, mechanical change with no new UI surface needed.
  - Blind spot: Haven't checked whether the backend treats a "logout that never reached the server" as a security concern worth surfacing (likely not, given cookie-based sessions expire regardless).
- **Fix B**: Add a visible inline error state and let the user retry.
  - Strength: Matches the explicit error-surfacing pattern used elsewhere (`deleteAnalysis`, `updateClauseDecision`).
  - Tradeoff: The header has no space budgeted for an error message today, and the app has no toast/notification system — this would be new UI, disproportionate to a rare edge case.
  - Confidence: MEDIUM — mechanically fine, but adds scope well beyond a one-line fix.
  - Blind spot: None significant.
- **Decision**: FIXED via Fix A

### F2 — E2E success criterion intermittently fails on an unrelated environment issue

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: `frontend/e2e/analysis-result.spec.ts:36` (this review's own verification run)
- **Detail**: The final full-suite run for this review was 12/13 — the same single test that failed intermittently during Phases 2 and 3, always with the identical signature (rendered clause text traces exactly to `CONTRACT_TEXT` in `fixtures.ts`, meaning a non-`e2e`-profile backend answered the request). This is thoroughly diagnosed and disclosed in `change.md`: reproduces identically at clean HEAD (before any code in this plan existed), and the same suite ran 13/13 clean during Phase 4's own verification once port 8080 was genuinely free. Not a code defect in this diff.
- **Fix**: None needed in this change. Resolving requires finding and stopping whatever repeatedly (re)binds port 8080 with a non-`e2e`-profile backend, outside this plan's scope.
- **Decision**: SKIPPED

### F3 — Disclosed regression: `frontend/public/.gitkeep` was not in the original plan

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: `frontend/public/.gitkeep`
- **Detail**: Phase 3's SVG deletion emptied `frontend/public/` entirely; since git doesn't track empty directories, the directory vanished from the working tree, breaking `frontend/Dockerfile:34`'s `COPY --from=build /app/public ./public`. Caught when the user ran `docker-compose up -d` mid-implementation. Fixed with a `.gitkeep` placeholder and verified via `docker compose build frontend`. Fully disclosed in `context/changes/app-navigation-header/change.md` under `## Notes`, and in the Phase 4 commit message.
- **Fix**: None needed — already fixed and disclosed at the time it was found.
- **Decision**: SKIPPED

### F4 — Pre-existing accessible-name collision on the analysis detail page (not introduced by this diff)

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: `frontend/src/app/(app)/analyses/[id]/page.tsx:176`
- **Detail**: The delete `AlertDialogTrigger` button's accessible name is plain "Usuń" — identical to the confirm dialog's own "Usuń" action once the dialog is open. The dashboard's equivalent trigger avoids this via `aria-label="Usuń analizę: {title}"` (added by a prior impl-review's F1 finding). This file's content is byte-for-byte unchanged by this plan (confirmed via `git show dae5e54` — only its path moved under `(app)/`), so it predates this change and is out of scope here, but it's the same finding class this codebase has hit twice before.
- **Fix**: Add a content-embedded `aria-label` to the trigger at `analyses/[id]/page.tsx:176`, matching the dashboard's convention — as a separate follow-up, not part of this change.
- **Decision**: SKIPPED

<!-- PLAN-REVIEW-REPORT -->
# Plan Review: App Navigation Header (S-05)

- **Plan**: `context/changes/app-navigation-header/plan.md`
- **Mode**: Deep
- **Date**: 2026-07-12
- **Verdict**: REVISE → **SOUND** (after triage; all 8 findings fixed)
- **Findings**: 0 critical, 5 warnings, 3 observations

## Verdicts

| Dimension | Verdict (at review) | After fixes |
|-----------|---------------------|-------------|
| End-State Alignment | PASS | PASS |
| Lean Execution | WARNING (F7) | PASS |
| Architectural Fitness | WARNING (F1) | PASS |
| Blind Spots | WARNING (F2, F3) | PASS |
| Plan Completeness | WARNING (F4, F5, F6, F8) | PASS |

## Grounding

7/7 paths ✓, 4/4 npm scripts ✓, brief↔plan ✓, Progress↔Phase consistency ✓ (4 phases, all criteria mapped).

Four of the plan's riskiest structural claims were independently verified against the code and **all confirmed**: the Fragment/flex-chain reasoning, the `git mv` import-safety and `proxy.ts` non-impact, the AlertDialog z-index stacking (dialog `z-50` vs header `z-10`), and "Phase 1 lands green" (all four "Nowa analiza" locators already carry `.first()`, so 3 matches would not have thrown).

## Findings

### F1 — Plan accepts the very name-collision it forbids

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Architectural Fitness
- **Location**: Critical Implementation Details vs. Phase 2, criterion 2.5
- **Detail**: The plan declares accessible-name collisions "this codebase's recurring bug class" and forbids them, then criterion 2.5 explicitly accepts *two* "Nowa analiza" controls on an empty dashboard (header CTA + empty-state CTA) with an identical accessible name and destination. It also permanently locks `.first()` into `fixtures.ts`.
- **Fix A ⭐ Recommended**: Make the empty state text-only; the header CTA covers the action from every screen.
  - Strength: Removes the collision at source; simplifies the dashboard.
  - Tradeoff: A first-time user's empty dashboard loses its inline button.
  - Confidence: MED — clean, but a real UX judgment on the first-run experience.
  - Blind spot: Nobody has watched a first-time user hunt for the CTA.
- **Fix B**: Keep both; rename the empty-state CTA (e.g. "Rozpocznij pierwszą analizę").
  - Strength: Preserves first-run discoverability.
  - Tradeoff: New Polish string; two buttons doing the same thing with different labels.
  - Confidence: HIGH — mechanically safe.
- **Decision**: FIXED via Fix A. Knock-on: `.first()` becomes unnecessary in all four locators (folded into Phase 4).

### F2 — Sticky header will visibly jump when the delete dialog opens

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 1 header contract; manual criterion 1.8
- **Detail**: The plan analyzed the AlertDialog interaction purely as a z-index question (correct: dialog `z-50`, header `z-10`). But Radix mounts it through `react-remove-scroll`, which sets `overflow: hidden` **and a scrollbar-compensating `padding-right`** on `<body>` while open — shifting the header's centered `mx-auto max-w-2xl` container sideways by ~half a scrollbar width the moment the dialog opens.
- **Fix**: Add `scrollbar-gutter: stable` to `html` in `globals.css`; extend manual check 1.8 to verify no horizontal shift.
- **Decision**: FIXED.

### F3 — Sticky header creates a new Playwright flake surface

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 1 (header) / Phase 4 (new specs)
- **Detail**: Playwright auto-scrolls via `scrollIntoViewIfNeeded` before clicking; a sticky header parks scrolled-to elements underneath itself, producing `element intercepts pointer events` timeouts. Current specs never scroll, but Phase 4 adds a populated-dashboard test and future multi-analysis specs are exposed. Same genus as the `f4295a7` flake — invisible until CI runs it twenty times.
- **Fix**: Add `scroll-padding-top: <header height>` to `html` in `globals.css`.
- **Decision**: FIXED. Combined with F2 into a single "sticky-header support CSS" change in Phase 1.

### F4 — Phase 1's route-group check can never fail

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 1, automated criterion 1.4
- **Detail**: `grep -r "(app)" frontend/src --include=*.ts --include=*.tsx` returns nothing *today*, before any work — verified by running it. `grep` searches file contents, not paths, and a route group is a directory name that by definition never appears inside a `.ts`/`.tsx` file. The check passes whether the group is created correctly, wrongly, or not at all. A guaranteed-pass masquerading as a URL-invariance guarantee.
- **Fix**: Replace with a directory assertion that can fail: `test -d "frontend/src/app/(app)/dashboard" && test ! -d "frontend/src/app/dashboard"`. (Criterion 1.3 — the E2E suite, incl. `auth-redirect.spec.ts` — is what actually proves the URLs held.)
- **Decision**: FIXED.

### F5 — Phase 2's dead-code grep can't see the failure that matters

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 2, automated criterion 2.4
- **Detail**: The grep covered `me|logout|CurrentUser|handleLogout` but not `user`/`setUser`/`loading`/`setLoading`. An implementer who removes `me()` but leaves the `loading` state and its `if (loading) return null` gate ships a dashboard whose body renders `null` forever — nothing calls `setLoading(false)` any more. ESLint won't flag it (the var is "used"), and the criterion returns nothing and "passes".
- **Fix**: Extend to `\b(me|logout|CurrentUser|handleLogout|user|setUser|loading|setLoading)\b`. Safe: `\bloading\b` cannot match `analysesLoading`.
- **Decision**: FIXED.

### F6 — Phase 2 under-specifies the dashboard edit

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Current State Analysis; Phase 2 contract
- **Detail**: (a) `user` is read not only at `:96` and the `:45` gate but also as an **effect dependency at `:56`**. (b) Removing `if (loading) return null` changes first paint twice, unstated: happy path blank-screen → skeletons (an improvement); 401 path skeletons → blank during redirect.
- **Fix**: Add the `:56` dependency to the enumeration; state both first-paint changes in Phase 2's contract.
- **Decision**: FIXED.

### F7 — Phase 3 orphans five template SVGs that ship in the image

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Lean Execution
- **Location**: Phase 3, criterion 3.4
- **Detail**: `frontend/public/{next,vercel,file,globe,window}.svg` are create-next-app assets whose only consumer is `app/page.tsx` — replaced by a redirect in Phase 3. They become dead weight still copied into the `output: "standalone"` image. Criterion 3.4 greps `frontend/src`, so it cannot see them, yet its label claims "no create-next-app remnants".
- **Fix**: Delete the five SVGs in Phase 3; add an assertion that they are gone.
- **Decision**: FIXED.

### F8 — "Zero specs reference Falcon" is false

- **Severity**: 💡 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Current State Analysis (plan.md:22)
- **Detail**: `disclaimer.spec.ts:19` holds a *live* `getByText("Falcon dostarcza analizę pomocniczą, a nie poradę prawną.")` locator. Harmless (a header link reading "Falcon" cannot *contain* that longer sentence), but the plan's own ban on `getByText("Falcon")` rests on precision here. Also: 7 spec files, not 8. Separately, all success-criteria greps use `\b` and escaped parens — Git-Bash-only, and this repo's primary shell is PowerShell.
- **Fix**: Correct the count and the claim; note the greps require the Bash tool.
- **Decision**: FIXED.

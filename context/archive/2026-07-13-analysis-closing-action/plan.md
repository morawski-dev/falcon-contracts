# Analysis closing action — Implementation Plan

## Overview

Make the *existing* route back to the dashboard **legible**. Today a logged-in user who finishes reading an analysis has no visible way onward: the report column simply stops after the last clause, and the only exit is a sticky wordmark that reads as branding rather than as navigation. This change adds two explicit, labelled affordances — a `Moje analizy` link in the app header, and a `Wróć do moich analiz` link closing the report — and nothing else.

Frontend-only. No new endpoint, no schema change, no change to the owner-scoping invariant.

## Current State Analysis

The way back is **not missing** — it is unrecognizable.

- `frontend/src/components/app-header.tsx:20` renders a sticky header (`sticky top-0`) that is present on every authenticated screen via the `(app)` route group. Its `Falcon` wordmark (`:22-27`) is already a `next/link` to `/dashboard` and never scrolls out of view.
- `frontend/e2e/app-header.spec.ts:4-8` states the design intent outright: *"the persistent AppHeader is the only way back to the dashboard from the analyze screens."* S-05 shipped exactly that, deliberately. It proved insufficient in use.
- `frontend/src/app/(app)/analyses/[id]/page.tsx:211-296` renders the report as one long single column of clauses. After the last clause (`last:border-b-0`) and the optional `Pozostałe uwagi` card, the page ends. At the exact moment the user has finished the task and asks "what now", the page offers nothing.
- The problem is worst immediately after creating an analysis, because the user arrives there by a **redirect** from `/analyses/new` — they did not navigate, so they have no mental model of where they are, and browser history holds the form, not a useful "back".
- `frontend/src/app/(app)/dashboard/page.tsx` is a bare list under the header. Nothing on it names the destination ("moje analizy"), so even a successful click lands without confirmation.

### Key Discoveries:

- **Playwright matches accessible names as a case-insensitive substring by default.** This cuts both ways here:
  - It means a *new* link whose accessible name contains an existing one would make `getByRole` strict-mode-ambiguous and break passing specs.
  - Concretely: `frontend/e2e/app-header.spec.ts:16`, `:25`, `:90`, `:94` all use `getByRole("link", { name: "Falcon" })`. Any new link containing the word "Falcon" breaks four assertions across three tests.
- **Two links to `/dashboard` will coexist on `/analyses/[id]`** (header + report footer). They MUST NOT share an accessible name, or `getByRole("link", { name: … })` matches both. This is the single most likely way to break the suite, and it is designed around below via distinct labels.
- The dashboard's own `Nowa analiza` CTA only renders on an **empty** list (`dashboard/page.tsx:75-84`); the header's CTA is the only one on a populated dashboard. `app-header.spec.ts:38-65` exists purely as the regression guard for that. The same discipline applies to anything added to the header now.
- The app has no `typecheck` script (`frontend/package.json:9-15`); `pnpm build` (Next 16) is what type-checks. Verification commands are `pnpm lint`, `pnpm build`, `pnpm test:e2e`.
- The E2E suite needs the deterministic backend profile (`CLAUDE.md`): `./mvnw spring-boot:run -Dspring-boot.run.profiles=e2e -Dspring-boot.run.additional-classpath-elements=target/test-classes`.

## Desired End State

On `/analyses/[id]`, a user who scrolls to the end of the report meets a clearly labelled `Wróć do moich analiz` link that takes them to `/dashboard`. Independently, from any authenticated screen, the header carries an explicit `Moje analizy` link alongside the `Falcon` wordmark, so the route back is named rather than implied.

Verified by: a green `pnpm test:e2e` — including a new `closing-action.spec.ts` that drives the end-of-report link, and an extended `app-header.spec.ts` that guards the new header link the way S-05 guards `Nowa analiza`.

## What We're NOT Doing

- **Not** removing or renaming the `Falcon` wordmark link, and not changing its accessible name.
- **Not** adding a closing action to `/analyses/new` — the form already has a submit action and is not a dead end.
- **Not** adding an exit to the "Nie znaleziono analizy" error state (`analyses/[id]/page.tsx:149-160`). It is a real gap, but it was explicitly scoped out; the header link (Phase 1) incidentally improves it.
- **Not** adding breadcrumbs, a sidebar, a sticky bottom bar, or a `Nowa analiza` button in the report footer.
- **Not** using `router.back()`. After the post-creation redirect it would return the user to the **form**, not the dashboard — the precise trap this change exists to close.
- **Not** touching the backend, the schema, or any owner-scoping query.

## Implementation Approach

Two thin frontend phases, sequenced so that the riskier one lands first.

Phase 1 introduces the new accessible name (`Moje analizy`) into the header. If adding a labelled link is going to disturb the existing `getByRole` locators anywhere, it will surface here — against a suite that does not yet contain a second dashboard link. Phase 2 then adds the report's closing link under a *deliberately different* label (`Wróć do moich analiz`), so the two can never collide in a locator.

Both links are plain `next/link` to `/dashboard`, styled inside S-06's system (the `stamp` / focus-ring vocabulary already used at `app-header.tsx:24` and `dashboard/page.tsx:93`).

## Critical Implementation Details

**Accessible-name collision is the load-bearing constraint.** `CLAUDE.md` mandates accessibility-first locators, so every accessible name in this app is part of a test contract. The two new links point at the same route but must never share a name: header = `Moje analizy`, report footer = `Wróć do moich analiz`. Do not "harmonize" these two strings into one during implementation — that is precisely what breaks strict mode.

## Phase 1: An explicitly labelled way back in the header

### Overview

The header gains a link that names its destination, so the route back no longer depends on a user reading a wordmark as navigation.

### Changes Required:

#### 1. App header

**File**: `frontend/src/components/app-header.tsx`

**Intent**: Add an explicit `Moje analizy` link to `/dashboard`, so the header names the destination instead of relying on the `Falcon` wordmark to imply it. The wordmark stays exactly as it is — same href, same accessible name, same styling.

**Contract**: A `next/link` to `/dashboard` with the visible (and accessible) name `Moje analizy`, placed in the header's action group beside `Nowa analiza` / `Wyloguj`. It must be a link (role `link`), not a `Button` with an `onClick`. Visual weight sits **below** `Nowa analiza` (which is the primary action) — e.g. a ghost/link-styled control — so the header reads as one primary action plus quiet navigation, not as a nav menu. Carry the same focus-visible ring already used at `app-header.tsx:24` and `dashboard/page.tsx:93`.

#### 2. Header regression guard

**File**: `frontend/e2e/app-header.spec.ts`

**Intent**: Guard the new link the same way `Nowa analiza` is guarded — S-05 learned that a header affordance with no dedicated test can be silently removed. Also assert it is absent from the unauthenticated screens, matching the existing shape of the last test in the file.

**Contract**: A test that, from `/analyses/[id]` (seeded via `seedAnalysis` in `e2e/fixtures.ts`), clicks `getByRole("link", { name: "Moje analizy" })` and awaits `**/dashboard`; plus an assertion in the existing "does not appear on the login or register pages" test that this link is not visible there. Clean up the seeded analysis, per the file's existing convention.

### Success Criteria:

#### Automated Verification:

- Lint passes: `cd frontend && pnpm lint`
- Type check + build passes: `cd frontend && pnpm build`
- Full E2E suite passes, including the extended header spec: `cd frontend && pnpm test:e2e` (with the backend on the `e2e` profile)
- No strict-mode violations: the four existing `getByRole("link", { name: "Falcon" })` assertions in `app-header.spec.ts` still resolve to exactly one element

#### Manual Verification:

- On `/analyses/[id]` and `/analyses/new`, the header shows `Moje analizy` and clicking it lands on the dashboard
- The header does not read as a nav menu — `Nowa analiza` still reads as the primary action
- `Moje analizy` is reachable by keyboard and shows a visible focus ring
- The header is still absent from `/login` and `/register`

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Close the report

### Overview

The report stops ending in nothing: after the last clause, the user is handed the way back.

### Changes Required:

#### 1. Analysis result page

**File**: `frontend/src/app/(app)/analyses/[id]/page.tsx`

**Intent**: Add a closing link at the foot of the report — after the clause list and after the optional `Pozostałe uwagi` card — that returns the user to `/dashboard`. This is the affordance the whole change exists for: the moment the reading ends is the moment the user asks "what now".

**Contract**: A `next/link` to `/dashboard` whose accessible name is `Wróć do moich analiz` — **deliberately different from the header's `Moje analizy`**, so `getByRole("link", { name: … })` stays unambiguous on this page. It renders only in the loaded-report branch (the `return` at `:174`), i.e. not in the loading skeleton and not in the not-found branch. It sits inside the existing `max-w-2xl` column, visually separated from the last clause (the report's own rules already use `border-b border-border`), and is styled inside S-06's system rather than as a stock button. No `router.back()`.

#### 2. Closing-action E2E spec

**File**: `frontend/e2e/closing-action.spec.ts` (new)

**Intent**: Prove the affordance this change is named after: a user who has read a saved analysis can leave it via the end-of-report link. A dedicated file (rather than an assertion buried in `analysis-result.spec.ts`) means a failure names the thing that broke.

**Contract**: Follow the existing spec conventions in `frontend/e2e/` — `registerFreshUser` + `seedAnalysis` from `./fixtures`, a timestamped unique title, role-based locators only, `waitForURL` rather than any timeout, and cleanup of the seeded analysis at the end. Assert: from a seeded `/analyses/[id]`, `getByRole("link", { name: "Wróć do moich analiz" })` is visible and clicking it lands on `**/dashboard`. Add a header comment naming the risk it covers, matching the style of `app-header.spec.ts:4-8`.

### Success Criteria:

#### Automated Verification:

- Lint passes: `cd frontend && pnpm lint`
- Type check + build passes: `cd frontend && pnpm build`
- Full E2E suite passes, including the new spec: `cd frontend && pnpm test:e2e`
- The new spec fails if the closing link is removed (verify by temporarily deleting it, or by confirming the spec was red before the page change)

#### Manual Verification:

- Paste a contract on `/analyses/new`, submit, and after the redirect scroll to the end of the report — the way back is visible and self-explanatory
- The closing link is absent from the loading skeleton and from the "Nie znaleziono analizy" state
- On a report with `Pozostałe uwagi`, the closing link still renders last
- Keyboard: the closing link is the last focusable element in the report and shows a focus ring

**Implementation Note**: After completing this phase and all automated verification passes, pause for manual confirmation from the human.

---

## Testing Strategy

### Integration / E2E Tests:

- `frontend/e2e/closing-action.spec.ts` (new): seeded analysis → click `Wróć do moich analiz` → land on `/dashboard`.
- `frontend/e2e/app-header.spec.ts` (extended): click `Moje analizy` from a saved analysis → land on `/dashboard`; the link is absent from `/login` and `/register`.

### Manual Testing Steps:

1. Log in, paste a contract, submit, wait for the redirect to `/analyses/[id]`.
2. Scroll to the end of the report — confirm the closing link is there and its label is unambiguous.
3. Click it; confirm you land on the dashboard and can see the analysis you just created.
4. Reload the report and use the header's `Moje analizy` instead; confirm the same landing.
5. Tab through the report to confirm both links are keyboard-reachable with a visible focus ring.

## References

- Roadmap slice: `context/foundation/roadmap.md` → **S-07: Finish the report and be shown the way back** (read its Risk note — it carries the diagnosis).
- Prior slice this builds on: `context/archive/2026-07-12-app-navigation-header/` (S-05).
- Design system this must be expressed in: `context/archive/2026-07-13-ui-design-system/` (S-06).
- E2E conventions and locator rules: `CLAUDE.md` → "10xDevs AI Toolkit - Module 3, Lesson 4".

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: An explicitly labelled way back in the header

#### Automated

- [x] 1.1 Lint passes: `cd frontend && pnpm lint` — 61cf9ea
- [x] 1.2 Type check + build passes: `cd frontend && pnpm build` — 61cf9ea
- [x] 1.3 Full E2E suite passes, including the extended header spec — 61cf9ea
- [x] 1.4 No strict-mode violations: existing `name: "Falcon"` locators still resolve to one element — 61cf9ea

#### Manual

- [x] 1.5 `Moje analizy` visible in the header and navigates to the dashboard — 61cf9ea
- [x] 1.6 Header does not read as a nav menu; `Nowa analiza` remains the primary action — 61cf9ea
- [x] 1.7 `Moje analizy` is keyboard-reachable with a visible focus ring — 61cf9ea
- [x] 1.8 Header still absent from `/login` and `/register` — 61cf9ea

### Phase 2: Close the report

#### Automated

- [x] 2.1 Lint passes: `cd frontend && pnpm lint` — ff26d2a
- [x] 2.2 Type check + build passes: `cd frontend && pnpm build` — ff26d2a
- [x] 2.3 Full E2E suite passes, including the new `closing-action.spec.ts` — ff26d2a
- [x] 2.4 The new spec fails if the closing link is removed — ff26d2a

#### Manual

- [x] 2.5 After the post-creation redirect, the end of the report offers a self-explanatory way back — ff26d2a
- [x] 2.6 Closing link absent from the loading skeleton and the not-found state — ff26d2a
- [x] 2.7 Closing link still renders last on a report with `Pozostałe uwagi` — ff26d2a
- [x] 2.8 Closing link is the last focusable element and shows a focus ring — ff26d2a

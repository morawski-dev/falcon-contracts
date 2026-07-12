# App Navigation Header (S-05) Implementation Plan

## Overview

Falcon's navigation graph is one-directional: the dashboard links out to the analyze screens, but neither `/analyses/new` nor `/analyses/[id]` offers any way back. A user who finishes reading an analysis is stranded on the browser back button.

This plan introduces an **authenticated app shell** — an `(app)` route group whose layout mounts a persistent, sticky `AppHeader` across `/dashboard`, `/analyses/new`, and `/analyses/[id]`, carrying the "Falcon" wordmark (→ `/dashboard`), a "Nowa analiza" CTA, and "Wyloguj". The dashboard's now-redundant chrome card is deleted, the root page's create-next-app dead end is closed, and the four E2E surfaces this change exposes are covered.

Frontend-only. No backend, no schema, no API change, no change to the owner-scoping invariant.

## Current State Analysis

- **One layout exists in the entire app.** `frontend/src/app/layout.tsx:20-33` — a server component that renders fonts and nothing else. No providers, no context, no query client; `children` is rendered raw. The `(auth)` route group has **no** `layout.tsx` and buys nothing structurally today.
- **The height chain is a load-bearing, undocumented contract.** `layout.tsx:28` sets `<html … h-full>`; `layout.tsx:30` sets `<body className="min-h-full flex flex-col">` — a **column flex container**. Every page root is a **direct flex child** of `<body>` using `flex-1` (`dashboard/page.tsx:89`, `analyses/new/page.tsx:55`, `analyses/[id]/page.tsx:160`, `(auth)/login/page.tsx:42`). This works only because no intermediate layout exists. This plan introduces the first one.
- **The real auth guard is `frontend/src/proxy.ts:1-14`** — Next 16's rename of `middleware.ts`. It redirects to `/login` when the `JSESSIONID` cookie is absent, for `matcher: ["/dashboard/:path*", "/analyses/:path*"]`. It checks cookie **presence only**, not validity.
- **The app is 100% client-side.** `frontend/src/lib/api.ts:12-23` — `apiFetch` reads `document.cookie` and sets `credentials: "include"`, so it cannot run on the server. Hence five pages, five `"use client"` directives, zero server actions.
- **`logout()` takes no arguments and does not navigate** (`frontend/src/lib/auth.ts:30-33`) — the caller redirects (`dashboard/page.tsx:58-61`).
- **The dashboard's chrome card** (`dashboard/page.tsx:91-106`) holds the wordmark `Falcon`, `Zalogowano jako {email}`, the `Nowa analiza` link, and the `Wyloguj` button.
- **`dashboard/page.tsx:37-42` calls `me()`** to populate `user`. `user` is read in exactly three places: the email at `:96`, the `getAnalyses()` gate at `:45`, and the **effect dependency array at `:56`**. `loading` is read only by the `if (loading) return null` gate at `:84-86`.
- **`frontend/src/app/page.tsx`** is still the untouched create-next-app template; `layout.tsx:15-18` still declares `title: "Create Next App"` and `lang="en"` despite an all-Polish UI.
- **`frontend/src/components/` contains only `ui/`** — 10 shadcn primitives. There is **no non-primitive component in the repo**.
- **E2E:** 7 spec files. **"Wyloguj" has zero references — logout has no coverage at all.** "Falcon" *is* referenced: `disclaimer.spec.ts:19` holds a live `getByText("Falcon dostarcza analizę pomocniczą, a nie poradę prawną.")` locator (harmless — a header link reading exactly "Falcon" cannot *contain* that longer sentence; the hazard runs the other way, hence the ban below). There are **no `getByRole("heading")` queries** anywhere, because shadcn's `CardTitle` renders a `<div>` (`components/ui/card.tsx:36-47`) — the app has zero `h1`–`h6` elements.
- **All four "Nowa analiza" locators already use `.first()`** (`fixtures.ts:38`, `disclaimer.spec.ts:16`, `analysis-input-validation.spec.ts:32,60`) — because the string already matches twice on an empty dashboard today.
- **Shell note:** the grep-based success criteria below use `\b` and escaped parens — run them with the **Bash tool (Git Bash)**, not PowerShell.

## Desired End State

A logged-in user on any authenticated screen sees a sticky header with the Falcon wordmark, a "Nowa analiza" button, and "Wyloguj". Clicking the wordmark returns them to `/dashboard` from anywhere. Clicking "Nowa analiza" starts a new analysis from anywhere — including from a *populated* dashboard and from the analysis result page. Logout works from every screen. `/login` and `/register` show no header. `/` no longer shows the Next.js starter.

**Verification:**
- `cd frontend && pnpm lint && pnpm build` — clean.
- `cd frontend && pnpm test:e2e` — all specs green, including the four new ones.
- Manual: open a long analysis, scroll to the bottom, confirm the header is still visible and its wordmark returns to the dashboard.

### Key Discoveries:

- **A Fragment-returning layout preserves the height chain for free.** `<><AppHeader />{children}</>` adds **no DOM node**, so `<header>` and the page's `flex-1` div both remain direct flex children of `body.flex-col`. Header takes natural height; pages keep filling the rest. **Zero CSS changes to any page.** Wrapping `children` in a `<div>` instead makes that wrapper the flex child, and the page's `flex-1` then resolves against a height-less box — collapsing the vertical centering on `/analyses/new`.
- **Route groups do not change URLs**, and nothing in the repo references these pages by file path — all navigation is by URL string (`router.push("/dashboard")` at `analyses/[id]/page.tsx:112`; `<Link href="/analyses/new">` at `dashboard/page.tsx:99`). `proxy.ts:13`'s matcher is URL-based. So the `git mv` into `(app)/` requires **no** edit to `proxy.ts` and breaks no import.
- **The header needs zero data.** `logout()` takes no args; the wordmark is a static link. Dropping "Zalogowano jako" means the header does no fetching — no `me()`, no React context (the app has none), no `src/hooks/`.
- **`dashboard/page.tsx`'s `me()` becomes orphaned** once the email is gone: `user` exists only for `:96` and the `getAnalyses()` gate at `:45-47`.
- **The E2E suite is blind to this change's regression.** Every spec clicks "Nowa analiza" on a *freshly registered, empty* dashboard, where the empty-state CTA at `dashboard/page.tsx:122` matches. Deleting the chrome card without a header CTA would leave a user *with* analyses no way to start a new one — and the suite would stay green.
- **`getByText("Falcon")` is unusable as a locator** — `getByText` substring-matches, and "Falcon" appears in body copy at `analyses/new/page.tsx:60`, `:104` and `analyses/[id]/page.tsx:165`.
- **The generalized flake rule** (commit `f4295a7`): wait on the network response, not a DOM proxy, whenever a test navigates right after a mutation.

## What We're NOT Doing

- **No user email in the header.** "Zalogowano jako {email}" is removed outright. This is a deliberate, small feature loss that buys the elimination of all header data-fetching.
- **No React context, no `src/hooks/`, no providers.** Not needed once the header is data-free.
- **No nav menu, breadcrumbs, sidebar, dropdown, or avatar.** Three affordances only. No new shadcn primitives are required.
- **No backend change.** No endpoint, no schema, no touch to the owner-scoping invariant.
- **No `middleware.ts`.** `src/proxy.ts` is the middleware; adding a second file would create two.
- **No landing page at `/`.** A redirect only — a public front door is a slice of its own.
- **No `useEffect` unmount/abort hardening.** `analysis-history`'s impl-review F4 deliberately deferred this across all three pages as a dedicated cleanup pass; we do not do it piecemeal here.
- **No i18n layer or strings file.** Polish copy stays hardcoded inline, per house convention.
- **No new Polish vocabulary.** There is no "Pulpit" in the app; the **wordmark is the affordance**.

## Implementation Approach

The `(app)` route group is the only option that yields a **single persistent layout instance** across all three authenticated routes — two sibling layouts (`dashboard/layout.tsx` + `analyses/layout.tsx`) would unmount and remount the header when crossing between `/dashboard` and `/analyses/*`.

The layout itself stays a **server component** that renders a `"use client"` `<AppHeader />`. Only the logout button needs the browser, so only the header is a client component — matching the codebase's "client only where the browser is required" instinct, even though the repo has no precedent either way.

`AppHeader` is the **first non-primitive component in the repo**. This deliberately inverts the established house pattern (`delete-analysis` and `analysis-history` both *duplicated* UI across screens rather than extracting it), and `analysis-history/plan.md:44` explicitly deferred it: *"No shared header/nav component … a global nav is out of scope."* This slice redeems that deferral, so the break with pattern is intentional and stated.

Phases are ordered so that **the header's "Nowa analiza" CTA exists before the dashboard's card is deleted** — otherwise there is a commit in which a populated dashboard has no way to start an analysis.

## Critical Implementation Details

**Fragment, not `<div>`.** `(app)/layout.tsx` must return `<><AppHeader />{children}</>`. Any wrapping element becomes the body's flex child and breaks every page's `flex-1` (see Key Discoveries). This is the single highest-risk line in the change.

**Sticky inside a flex column, and its two side-effects.** The header uses `sticky top-0 z-10`. Its scroll container is the viewport (`html.h-full` / `body.min-h-full`), and a flex child can be sticky, so this works — verified: no ancestor sets `overflow`, `transform`, or `filter`. Radix's `AlertDialog` portals to `document.body` at **z-50** (`components/ui/alert-dialog.tsx:39,61`), so it correctly overlays the header's `z-10`.

But z-index is not the only mechanism sticky positioning introduces, and **both of the others need a one-line CSS guard** (see Phase 1, change 4):

1. **Radix's scroll-lock shifts the header sideways.** `AlertDialog` mounts through `react-remove-scroll`, which — while the dialog is open — sets `overflow: hidden` **plus a scrollbar-compensating `padding-right`** on `<body>`. That nudges the header's centered `mx-auto max-w-2xl` container horizontally by ~half a scrollbar width the instant the delete dialog opens: a visible jump on a sticky bar. `scrollbar-gutter: stable` on `html` makes the compensation a no-op.
2. **Sticky headers park Playwright's click targets underneath themselves.** Playwright auto-scrolls via `scrollIntoViewIfNeeded` before clicking; an element scrolled to the viewport's top edge lands *under* the sticky bar, producing `element intercepts pointer events` timeouts. Today's specs never scroll (fresh account, one analysis), but Phase 4 adds a populated-dashboard test and any future multi-analysis or long-clause spec is exposed — this is the same genus as the `f4295a7` flake: invisible until CI runs it twenty times. `scroll-padding-top` on `html` prevents the whole class.

**Accessible-name collisions are this codebase's recurring bug class.** Two of the last three impl-reviews flagged ambiguous accessible names, both invisible to manual testing. A component on *every* page is a collision machine: the header must not introduce any control whose accessible name contains **"Usuń"** (five specs use an unscoped `getByRole("button", { name: "Usuń" })`, and `name` is substring-matched), must not render the analysis title (would make `getByText(title)` ambiguous in `analysis-history.spec.ts:15,23`), and must not use `role="alert"`.

---

## Phase 1: Authenticated shell — `(app)` route group + `AppHeader`

### Overview

Create the route group, move the authenticated routes into it, and mount the header. **Land this alone against the currently-green suite** so any breakage is attributable to the move, not to UI edits stacked on top. After this phase, `/dashboard` briefly shows *both* the new header and its old chrome card — that duplication is expected and is resolved in Phase 2.

### Changes Required:

#### 1. Move the authenticated routes into a route group

**Files**: `frontend/src/app/dashboard/` → `frontend/src/app/(app)/dashboard/`, `frontend/src/app/analyses/` → `frontend/src/app/(app)/analyses/`

**Intent**: Establish a shared parent for the three authenticated screens so a single layout can mount the header once, persistently, across all of them.

**Contract**: Use `git mv` to preserve history. **URLs are unchanged** — `(app)` is parenthesized and stripped from the path. `frontend/src/app/page.tsx` and `frontend/src/app/(auth)/` stay where they are. No import in the repo points into `app/`, and `proxy.ts`'s matcher is URL-based, so **nothing else needs editing**.

#### 2. The authenticated layout

**File**: `frontend/src/app/(app)/layout.tsx` (new)

**Intent**: Mount the header above every authenticated page while preserving the body's flex-column height chain.

**Contract**: A **server component** (no `"use client"`) exporting a default layout that takes `{ children }: { children: React.ReactNode }` and returns a **Fragment**:

```tsx
<>
  <AppHeader />
  {children}
</>
```

The Fragment is load-bearing — it emits no DOM node, so `<header>` and each page's `flex-1` root remain direct flex children of `<body className="min-h-full flex flex-col">`. Do **not** wrap `children`.

#### 3. The header component

**File**: `frontend/src/components/app-header.tsx` (new — the repo's first non-primitive component)

**Intent**: Give every authenticated screen a persistent way back to the dashboard, a way to start a new analysis, and a way to log out.

**Contract**: A `"use client"` component (it calls `logout()` from `@/lib/auth`, which reaches `document.cookie`, and `useRouter` to redirect). It fetches nothing and takes no props.

Renders a `<header>` landmark (implicit `role="banner"`), `sticky top-0 z-10` with `bg-background` and `border-b border-border`, whose inner container reuses the app's column idiom — `mx-auto flex w-full max-w-2xl items-center justify-between px-6` — so the wordmark lines up with the page content beneath it.

Three affordances, whose accessible names are the E2E contract:
- **Wordmark** — a `next/link` to `/dashboard` with accessible name exactly **`Falcon`**, styled `font-semibold` (do **not** use `font-sans`/`font-heading`; `--font-sans` is self-referential and broken at `globals.css:10`). Reuse the non-button focus convention from `dashboard/page.tsx:136`: `rounded outline-none focus-visible:ring-3 focus-visible:ring-ring/50`.
- **`Nowa analiza`** — `<Button asChild size="sm">` wrapping `<Link href="/analyses/new">`, mirroring the existing pattern at `dashboard/page.tsx:98-100`.
- **`Wyloguj`** — `<Button variant="outline" size="sm">` whose handler awaits `logout()` then `router.push("/login")`, lifted verbatim from `dashboard/page.tsx:58-61`.

No control here may carry an accessible name containing "Usuń", and no `role="alert"`.

#### 4. Sticky-header support CSS

**File**: `frontend/src/app/globals.css` (edit)

**Intent**: Neutralize the two side-effects that sticky positioning introduces (see Critical Implementation Details) before they surface as a visible layout jump and a CI flake.

**Contract**: Two declarations on `html` in the base layer (`:120-130`), alongside the existing `@apply font-sans`:
- `scrollbar-gutter: stable` — reserves the scrollbar gutter permanently, so Radix's scroll-lock `padding-right` compensation becomes a no-op and the header does not shift sideways when the delete dialog opens.
- `scroll-padding-top: <header height>` — keeps `scrollIntoViewIfNeeded` from parking scroll targets underneath the sticky header. Must match the header's actual height; state that height explicitly in the header component so the two stay in sync.

### Success Criteria:

#### Automated Verification:

- Lint passes: `cd frontend && pnpm lint`
- Build + typecheck passes: `cd frontend && pnpm build`
- The existing E2E suite still passes unchanged: `cd frontend && pnpm test:e2e` — this is what actually proves the route group preserved the URLs (`auth-redirect.spec.ts` exercises `/dashboard` and `/analyses/*`)
- The route group exists and the old directories are gone: `test -d "frontend/src/app/(app)/dashboard" && test -d "frontend/src/app/(app)/analyses" && test ! -d "frontend/src/app/dashboard" && test ! -d "frontend/src/app/analyses"`
- `frontend/src/proxy.ts` is unmodified: `git diff --exit-code frontend/src/proxy.ts`

#### Manual Verification:

- `/dashboard`, `/analyses/new`, `/analyses/[id]` all render the header; `/login` and `/register` do not.
- The header sticks to the top while scrolling a long analysis (open one with several clauses and scroll to the bottom).
- Open the delete-confirm `AlertDialog`: it overlays the header, **and the header does not jump horizontally** when it opens or closes.
- `/analyses/new` still vertically centers its card in the space below the header — the height chain is intact.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding.

---

## Phase 2: Shed the dashboard's redundant chrome

### Overview

Remove the card the header replaces, and the now-orphaned data fetching behind it. The header's "Nowa analiza" CTA (Phase 1) must already be live before this lands.

### Changes Required:

#### 1. Delete the chrome card and its data dependency

**File**: `frontend/src/app/(app)/dashboard/page.tsx` (edit)

**Intent**: Remove the wordmark/identity/actions card now duplicated by the header, and with it the `me()` call whose only remaining purpose was rendering the email and gating the analyses fetch.

**Contract**: Delete the `<Card>` at `:91-106` in its entirety, `handleLogout` at `:58-61`, and the `me()` `useEffect` at `:37-42` along with the `user` / `loading` state, the `if (loading) return null` gate at `:84-86`, **and `user` from the `getAnalyses` effect's dependency array at `:56`**.

`getAnalyses()` now runs in a mount-only `useEffect` (deps `[router]`), keeping its existing 401 → `/login` branch — which, together with `proxy.ts`, is the complete guard. Drop the now-unused `me`, `logout`, and `CurrentUser` imports from `@/lib/auth` (`:21`).

**Two intentional first-paint changes** — neither breaks anything, both are consequences of removing the `loading` gate that today blanks the *entire* page until `me()` resolves:
- **Happy path:** first paint goes from a blank white screen to the `analysesLoading` skeletons. (Today those skeletons are only ever visible in the narrow window *between* `me()` and `getAnalyses()` resolving; now they are the first thing rendered.) An improvement, but a visible change.
- **401 path:** goes from skeletons-forever to a blank body during the redirect, since `getAnalyses()`'s `.finally` clears `analysesLoading` while `analyses` stays `null`. Cosmetic and sub-second.

**Also remove the empty-state "Nowa analiza" CTA** (`:121-123`), leaving the empty state as explanatory text only. The header's CTA now covers this action from every screen, and keeping both would put two controls with an identical accessible name and an identical destination on the same page — the exact collision class this plan forbids in Critical Implementation Details. After this, "Nowa analiza" matches **exactly one** element on the dashboard in every state.

**Keep**: the `analysesLoading` skeleton, the empty-state card and its explanatory copy, the analyses list, and per-row delete.

The dashboard's shape after this is the same as `/analyses/[id]`: fetch on mount, 401 → `/login`.

### Success Criteria:

#### Automated Verification:

- Lint passes: `cd frontend && pnpm lint`
- Build + typecheck passes: `cd frontend && pnpm build`
- Existing E2E suite passes: `cd frontend && pnpm test:e2e`
- No dead references remain — note this must also catch a *stranded* `loading` state, which would render the dashboard body `null` forever with nothing left to call `setLoading(false)`: `grep -nE "\b(me|logout|CurrentUser|handleLogout|user|setUser|loading|setLoading)\b" "frontend/src/app/(app)/dashboard/page.tsx"` returns nothing. (Safe: `\bloading\b` cannot match `analysesLoading`.)

#### Manual Verification:

- "Nowa analiza" appears **exactly once** on the dashboard — populated or empty — and "Wyloguj" exactly once.
- The dashboard issues **no** `GET /api/auth/me` at all (check the Network tab; it should now issue only `GET /api/analyses`).
- Logging out from the dashboard still lands on `/login`.
- A user with saved analyses can start a new one (this is the regression the suite cannot see — verify by hand).
- An empty dashboard still explains itself, and the header's CTA is the way forward.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding.

---

## Phase 3: Fix the root dead end

### Overview

`/` is still the create-next-app starter and the browser tab still reads "Create Next App". A "Falcon" wordmark reads as *home*; home should be the dashboard. These are disclosed, deliberate extras — adjacent app-shell chrome, written into the plan rather than smuggled into a commit message.

### Changes Required:

#### 1. Redirect the root

**File**: `frontend/src/app/page.tsx` (replace)

**Intent**: Kill the Next.js template dead end and make the wordmark's "home" coherent.

**Contract**: Replace the entire template with a server component that calls `redirect("/dashboard")` from `next/navigation`. Note this page sits **outside** the `(app)` group, so it gets no header — correct, since it renders nothing. An unauthenticated visitor is redirected to `/dashboard`, which `proxy.ts` then bounces to `/login`; two hops, correct outcome.

#### 2. Correct the root metadata

**File**: `frontend/src/app/layout.tsx` (edit)

**Intent**: The browser tab currently says "Create Next App", and the document is declared `lang="en"` while the entire UI is Polish — which mis-cues screen readers' pronunciation.

**Contract**: Set `metadata.title` to `Falcon` and `metadata.description` to a one-line Polish description of the product (`:15-18`). Change `<html lang="en">` to `lang="pl"` (`:26`). Nothing else in this file changes — **do not** touch the body's `min-h-full flex flex-col` classes (the height chain depends on them) or the `scrollbar-gutter` / `scroll-padding-top` added in Phase 1.

#### 3. Delete the orphaned template assets

**Files**: `frontend/public/next.svg`, `vercel.svg`, `file.svg`, `globe.svg`, `window.svg` (delete)

**Intent**: These five create-next-app assets have exactly one consumer — `app/page.tsx`, which change 1 replaces with a redirect. Once it's gone they are dead weight that still gets copied into the `output: "standalone"` image.

**Contract**: Delete all five. Verify no remaining reference: they must not appear anywhere in `frontend/src` after change 1 lands.

### Success Criteria:

#### Automated Verification:

- Lint passes: `cd frontend && pnpm lint`
- Build + typecheck passes: `cd frontend && pnpm build`
- Existing E2E suite passes: `cd frontend && pnpm test:e2e`
- No create-next-app remnants in source: `grep -ri "create next app\|nextjs.org\|vercel" frontend/src` returns nothing (today this matches `layout.tsx:16-17` and six lines in `page.tsx` — both files are rewritten here, so it is the only automated check that catches a missed metadata edit)
- The orphaned assets are gone: `ls frontend/public/{next,vercel,file,globe,window}.svg 2>/dev/null` returns nothing

#### Manual Verification:

- Visiting `/` while logged in lands on `/dashboard`; while logged out it lands on `/login`.
- The browser tab reads "Falcon".

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding.

---

## Phase 4: E2E coverage

### Overview

Cover the slice's outcome and the three surfaces it exposes — including logout, which has **zero** coverage today, and "new analysis from a populated dashboard", the blind spot that would let this slice's regression ship green.

### Changes Required:

#### 1. Header spec

**File**: `frontend/e2e/app-header.spec.ts` (new)

**Intent**: Prove S-05's outcome and lock the guarantees that keep the header from breaking other specs.

**Contract**: Five independent tests, each registering its own timestamped user via `registerFreshUser` (`fixtures.ts:16-28`) and **cleaning up any analysis it seeds** (both prior impl-reviews flagged missing E2E cleanup):

1. **Return to the dashboard from `/analyses/new`** — navigate there, click the wordmark, `waitForURL("**/dashboard")`.
2. **Return to the dashboard from `/analyses/[id]`** — `seedAnalysis`, click the wordmark, `waitForURL("**/dashboard")`.
3. **Start a new analysis from a *populated* dashboard** — `seedAnalysis`, return to `/dashboard`, click "Nowa analiza" *while the list is non-empty*, `waitForURL("**/analyses/new")`. **This is the regression guard**; without it the suite cannot see the bug.
4. **Logout from the header** — click "Wyloguj", then `goto("/dashboard")` and assert the redirect back to `/login`.
5. **No header on the auth pages** — on `/login` and `/register`, assert the "Falcon" link and "Wyloguj" button are not visible. This is the only thing proving the `(app)` group actually scopes the header.

**Locator contract**: `getByRole("link", { name: "Falcon" })` and `getByRole("button", { name: "Wyloguj" })`. **Never `getByText("Falcon")`** — it substring-matches the disclaimer copy on three pages.

**Race contract for test 4**: the logout POST must be awaited, not inferred from the URL —
```ts
await Promise.all([
  page.waitForResponse(res =>
    res.url().includes("/api/auth/logout") && res.request().method() === "POST" && res.ok()),
  page.getByRole("button", { name: "Wyloguj" }).click(),
]);
```
This is the same class of race that made `delete-analysis.spec.ts` flaky (3/15 failures before the fix in commit `f4295a7`): navigating immediately after a mutation cancels the in-flight request.

#### 2. Drop the now-unnecessary `.first()` disambiguation

**Files**: `frontend/e2e/fixtures.ts` (`:36-38`), `frontend/e2e/disclaimer.spec.ts` (`:16`), `frontend/e2e/analysis-input-validation.spec.ts` (`:32`, `:60`)

**Intent**: All four "Nowa analiza" locators carry `.first()` solely because the string matched twice on an empty dashboard (the chrome card's link and the empty-state CTA). Phase 2 removed both — the header's CTA is now the **only** "Nowa analiza" on the page, in every state. `.first()` is now noise that silently hides any future ambiguity, and `fixtures.ts:36-37`'s comment explaining it is falsified by this very change (stale comments are a repeat impl-review finding).

**Contract**: Remove `.first()` from all four locators and delete the explanatory comment at `fixtures.ts:36-37`. The locators become plain `getByRole("link", { name: "Nowa analiza" })` — and now genuinely strict, so a future duplicate fails loudly instead of being silently swallowed.

### Success Criteria:

#### Automated Verification:

- Full suite green: `cd frontend && pnpm test:e2e`
- No CSS/XPath locators: `grep -rE "locator\(['\"][.#]" frontend/e2e/` returns nothing
- No hard waits: `grep -r "waitForTimeout" frontend/e2e/` returns nothing
- No forbidden text locator for the wordmark: `grep -r 'getByText("Falcon")' frontend/e2e/` returns nothing
- The `.first()` disambiguation is gone: `grep -rn "Nowa analiza\"\s*}).first()" frontend/e2e/` returns nothing
- Lint passes: `cd frontend && pnpm lint`

#### Manual Verification:

- Run the suite 3× consecutively with no flakes (the logout test is the new mutation-then-navigate surface, and the sticky header is a new pointer-interception surface).
- The new spec fails if the header's "Nowa analiza" CTA is removed (verify the regression guard actually guards — comment it out and watch test 3 go red).

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding.

---

## Testing Strategy

### Unit Tests:

None. There is no frontend unit-test harness in this repo, and the change has no pure logic worth one — the header is markup plus a two-line logout handler already covered end-to-end.

### Integration Tests:

None. No backend surface changes.

### End-to-end Tests:

The five tests in `frontend/e2e/app-header.spec.ts` (Phase 4). Key edge cases they encode:
- The header's presence on all three authenticated routes and **absence** on both auth routes.
- The "Nowa analiza" affordance from a **populated** dashboard — the only test that can see this slice's regression.
- Logout, awaited on the network response rather than a DOM proxy.

### Manual Testing Steps:

1. Log in; confirm the header appears on `/dashboard` with the wordmark, "Nowa analiza", and "Wyloguj" — and that the old chrome card is gone.
2. Paste a long contract (several clauses) and open the result. Scroll to the bottom; confirm the header remains stuck to the top.
3. Click the wordmark from the bottom of that page; confirm you land on `/dashboard`.
4. With at least one saved analysis on the dashboard, click "Nowa analiza"; confirm it opens the form. (This is the regression the automated suite is blind to.)
5. Open the delete-confirm dialog on an analysis; confirm the dialog overlays the header, not vice versa.
6. Visit `/login` while logged out; confirm no header, and no "Wyloguj" button.
7. Visit `/`; confirm you land on `/dashboard` (or `/login` if signed out), not the Next.js starter.
8. Check the browser tab reads "Falcon".

## Performance Considerations

Net negative cost. The dashboard loses one HTTP round-trip (`GET /api/auth/me`) and the header adds none — it fetches nothing. `sticky` positioning is compositor-friendly and the header is a handful of nodes.

## Migration Notes

No data migration. The `git mv` into `(app)/` changes no URL, so no redirects or bookmarks break. Rollback is `git revert` — the route group is the only structural change and it is self-contained.

## References

- Research: `context/changes/app-navigation-header/research.md`
- Roadmap slice S-05: `context/foundation/roadmap.md`
- E2E cookbook: `context/foundation/test-plan.md` §6.6
- Closest structural precedent (cross-screen UI change, house plan format): `context/archive/2026-07-10-delete-analysis/plan.md`
- The deferral this slice redeems: `context/archive/2026-07-08-analysis-history/plan.md:44`
- The accessible-name finding class: `context/archive/2026-07-10-delete-analysis/reviews/impl-review.md` (F1)
- The mutation-then-navigate race rule: commit `f4295a7`
- The height chain: `frontend/src/app/layout.tsx:28-30`
- The auth guard: `frontend/src/proxy.ts:1-14`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Authenticated shell — `(app)` route group + `AppHeader`

#### Automated

- [x] 1.1 Lint passes: `cd frontend && pnpm lint` — dae5e54
- [x] 1.2 Build + typecheck passes: `cd frontend && pnpm build` — dae5e54
- [x] 1.3 Existing E2E suite still passes unchanged: `cd frontend && pnpm test:e2e` — dae5e54
- [x] 1.4 The route group exists and the old directories are gone (`test -d "(app)/dashboard"` etc.) — dae5e54
- [x] 1.5 `frontend/src/proxy.ts` is unmodified — dae5e54

#### Manual

- [x] 1.6 Header renders on the three authenticated routes; absent on `/login` and `/register` — dae5e54
- [x] 1.7 Header sticks to the top while scrolling a long analysis — dae5e54
- [x] 1.8 The delete-confirm AlertDialog overlays the header, and the header does not jump horizontally when it opens — dae5e54
- [x] 1.9 `/analyses/new` still vertically centers its card — the height chain is intact — dae5e54

### Phase 2: Shed the dashboard's redundant chrome

#### Automated

- [x] 2.1 Lint passes: `cd frontend && pnpm lint` — 1639969
- [x] 2.2 Build + typecheck passes: `cd frontend && pnpm build` — 1639969
- [x] 2.3 Existing E2E suite passes: `cd frontend && pnpm test:e2e` (7/8; the one failure is the pre-existing environment issue documented in `change.md`, unrelated to this phase) — 1639969
- [x] 2.4 No dead `me` / `logout` / `CurrentUser` / `handleLogout` / `user` / `setUser` / `loading` / `setLoading` references remain in the dashboard — 1639969

#### Manual

- [x] 2.5 "Nowa analiza" appears exactly once on the dashboard in every state; "Wyloguj" exactly once — 1639969
- [x] 2.6 The dashboard issues no `GET /api/auth/me` — only `GET /api/analyses` — 1639969
- [x] 2.7 Logging out from the dashboard still lands on `/login` — 1639969
- [x] 2.8 A user with saved analyses can start a new one — 1639969
- [x] 2.9 An empty dashboard still explains itself, and the header's CTA is the way forward — 1639969

### Phase 3: Fix the root dead end

#### Automated

- [x] 3.1 Lint passes: `cd frontend && pnpm lint` — b38197a
- [x] 3.2 Build + typecheck passes: `cd frontend && pnpm build` — b38197a
- [x] 3.3 Existing E2E suite passes: `cd frontend && pnpm test:e2e` (7/8; the one failure is the pre-existing environment issue documented in `change.md`, unrelated to this phase) — b38197a
- [x] 3.4 No create-next-app remnants remain in `frontend/src` — b38197a
- [x] 3.5 The orphaned template SVGs are deleted from `frontend/public/` — b38197a

#### Manual

- [x] 3.6 `/` lands on `/dashboard` when logged in, `/login` when logged out — b38197a
- [x] 3.7 The browser tab reads "Falcon" — b38197a

### Phase 4: E2E coverage

#### Automated

- [x] 4.1 Full suite green: `cd frontend && pnpm test:e2e` (13/13) — 0fbd3ca
- [x] 4.2 No CSS/XPath locators in `frontend/e2e/` — 0fbd3ca
- [x] 4.3 No `waitForTimeout` in `frontend/e2e/` — 0fbd3ca
- [x] 4.4 No `getByText("Falcon")` in `frontend/e2e/` — 0fbd3ca
- [x] 4.5 The `.first()` disambiguation on "Nowa analiza" is gone — 0fbd3ca
- [x] 4.6 Lint passes: `cd frontend && pnpm lint` — 0fbd3ca

#### Manual

- [x] 4.7 Suite runs 3× consecutively with no flakes — 0fbd3ca
- [x] 4.8 The regression guard actually guards — removing the header CTA turns test 3 red — 0fbd3ca

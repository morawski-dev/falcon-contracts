---
date: 2026-07-12T23:00:39+02:00
researcher: Mateusz Morawski
git_commit: f4295a79cf6d049f251c1a946a31a46fe1c3e522
branch: main
repository: falcon-contracts
topic: "App navigation header — shared authenticated shell so users can return to the dashboard from the analyze screens (roadmap S-05)"
tags: [research, codebase, frontend, nextjs, routing, layout, auth, e2e, playwright, navigation]
status: complete
last_updated: 2026-07-12
last_updated_by: Mateusz Morawski
---

# Research: App navigation header (S-05)

**Date**: 2026-07-12T23:00:39+02:00
**Researcher**: Mateusz Morawski
**Git Commit**: `f4295a79cf6d049f251c1a946a31a46fe1c3e522` (pushed to `origin/main`)
**Branch**: main
**Repository**: falcon-contracts

## Research Question

The analyze screens (`/analyses/new`, `/analyses/[id]`) have no way back to the dashboard — the navigation graph is one-directional. Roadmap slice **S-05 (`app-navigation-header`)** closes this with a persistent authenticated app header (a "Falcon" wordmark linking to `/dashboard`, plus logout), present on every authenticated screen and absent from login/register.

Research four surfaces before planning: **(1)** layout & auth plumbing, **(2)** E2E blast radius, **(3)** prior art & conventions, **(4)** visual/design direction.

## Summary

The change is smaller than it looks structurally, and larger than it looks in one specific place.

**Structurally it is clean.** There is exactly one layout in the whole app (`src/app/layout.tsx`), whose `<body className="min-h-full flex flex-col">` is a column flex container, and every page root is a direct flex child using `flex-1`. An `(app)` route group whose layout returns a **Fragment** (`<><AppHeader/>{children}</>`) inserts the header as a sibling in that same flex column — the header takes its natural height, every page keeps `flex-1`, and **not one page needs a CSS change**. Route groups don't alter URLs, and nothing in the repo references these pages by file path, so `git mv dashboard/ analyses/ → (app)/` is safe.

**Three findings will surprise an implementer:**

1. **The middleware is `src/proxy.ts`, not `middleware.ts`** — the Next 16 rename. It is the *real* auth guard for `/dashboard` and `/analyses/*`, and it checks only that a `JSESSIONID` cookie *exists*, not that it's valid. Route groups don't affect its URL-based matcher, so it needs no edit.

2. **The header probably needs no data at all.** `logout()` takes no arguments and the wordmark is a static link — so a wordmark+logout header can be a `"use client"` component with **zero fetching**. Only the decision to *display the user's email* ("Zalogowano jako…") forces a `me()` call, and since `dashboard/page.tsx:37` already calls `me()` — and *sequences* its `getAnalyses()` on the result — a naive layout-level `me()` would fire **two `/api/auth/me` requests per dashboard load**. This is the main architectural fork in the slice.

3. **The load-bearing risk is "Nowa analiza", not "Wyloguj".** Deleting the dashboard's header card (`dashboard/page.tsx:91-106`) removes the *only* "Nowa analiza" affordance available to a user who **has** analyses — the empty-state CTA at `:122` only renders when the list is empty. **The E2E suite will not catch this**, because every spec clicks "Nowa analiza" on a freshly-registered, empty dashboard. If the header carries only wordmark+logout, a returning user with saved analyses has **no way to start a new one**, and the suite stays green while shipping it.

**E2E blast radius is otherwise near-zero.** No spec asserts on "Falcon" or "Wyloguj" — logout has *zero* coverage today. The suite breaks only if the header adds specific things (an analysis title, anything named "Usuń", a `role="alert"`); see the constraint table below.

## Detailed Findings

### 1. Layout & routing structure

**The complete route inventory** (`frontend/src/app/` — 5 pages, **1 layout**):

| File | URL | Component type |
|---|---|---|
| `src/app/layout.tsx` | root layout — **the only layout in the tree** | server |
| `src/app/page.tsx` | `/` | server — **still the untouched create-next-app template** |
| `src/app/(auth)/login/page.tsx` | `/login` | client |
| `src/app/(auth)/register/page.tsx` | `/register` | client |
| `src/app/dashboard/page.tsx` | `/dashboard` | client |
| `src/app/analyses/new/page.tsx` | `/analyses/new` | client |
| `src/app/analyses/[id]/page.tsx` | `/analyses/:id` | client |

- **The `(auth)` route group has no `layout.tsx`.** It is a bare organizational folder — it buys nothing structurally today.
- **The root layout is minimal and provider-free** (`src/app/layout.tsx:20-33`): Geist fonts → CSS vars, then `<html … h-full antialiased>` / `<body className="min-h-full flex flex-col">{children}</body>`. **No context, no theme provider, no query client — `children` is rendered raw.**
- `src/app/layout.tsx:15-18` — `metadata` is still boilerplate `title: "Create Next App"`, and `lang="en"` despite an all-Polish UI.

**Where the authenticated layout can go — two options:**

- **(A) `(app)` route group** — `git mv src/app/dashboard → src/app/(app)/dashboard`, same for `analyses/`, add `src/app/(app)/layout.tsx`. **URLs are unchanged** (parenthesized groups are stripped from the path). Confirmed safe: all navigation is by URL string (`router.push("/dashboard")` at `analyses/[id]/page.tsx:112`; `<Link href="/analyses/new">` at `dashboard/page.tsx:99`), and `src/proxy.ts:13`'s matcher is URL-based, not file-based. **This is the only option that yields one persistent layout instance across all three authenticated routes.**
- **(B) Two sibling layouts, no file moves** — `dashboard/layout.tsx` + `analyses/layout.tsx`, both rendering `<AppHeader/>`. No `git mv`, but the header **unmounts and remounts** when crossing between `/dashboard` and `/analyses/*` (different layout subtrees), so any layout-held state (a cached `me()`) would not persist across that navigation.

### 2. The flex/height chain — why the header insertion is nearly free

The chain, top to bottom:

1. `layout.tsx:28` — `<html className="… h-full antialiased">` → html is exactly viewport height.
2. `layout.tsx:30` — `<body className="min-h-full flex flex-col">` → **column flex container**. Note: no `min-h-screen`; the app relies on `html.h-full` + `body.min-h-full`.
3. Every page is a **direct flex child of `<body>`** (no intermediate layout exists).
4. Every page root uses **`flex-1`** to consume the free height.

Exact page containers:

| File | Root container |
|---|---|
| `dashboard/page.tsx:89` | `flex flex-1 justify-center p-6` (top-aligned) |
| `analyses/new/page.tsx:55` | `flex flex-1 items-center justify-center p-6` (**vertically centered**) |
| `analyses/[id]/page.tsx:160` | `flex flex-1 justify-center p-6` |
| `(auth)/login/page.tsx:42` | `flex flex-1 items-center justify-center p-6` (**vertically centered**) |

Inner column: `w-full max-w-2xl` (`max-w-sm` for auth). Gutter `p-6`, `gap-4` between cards.

**The decisive detail:**

- A layout rendering a **Fragment** — `<><AppHeader /><>{children}</></>` — adds **no DOM node**, so `<header>` and the page's `flex-1` div both remain direct flex children of `body.flex-col`. Header takes natural height, page fills the rest. **Zero class changes to any page.** ✅
- A layout that **wraps children in a `<div>`** makes that wrapper the body's flex child, and the page's `flex-1` then resolves against a wrapper with no defined height — **collapsing the vertical centering on `/analyses/new`** and losing the full-height fill. If a wrapper is used it needs `flex flex-1 flex-col`. ⚠️
- Side effect either way: on `/analyses/new`, "vertically centered" now means centered in *viewport minus header*, shifting the card up by roughly the header height. Expected and acceptable.
- `body` carries only `bg-background`, so a header needing visual separation must bring its own `border-b border-border` / `bg-background`.

### 3. Auth plumbing — and the `me()` fork

`frontend/src/lib/auth.ts` (39 lines):
- `CurrentUser = { id: number; email: string }` (`:3-6`) — **email is the only displayable field**; no name, no avatar.
- `logout()` (`:30-33`) — `getCsrf()` then POST `/api/auth/logout`. **Returns void, takes no arguments, does not navigate** — the caller redirects (`dashboard/page.tsx:58-61`).
- `me()` (`:35-38`) — GET `/api/auth/me`; throws `ApiError(401)` when unauthenticated.

`frontend/src/lib/api.ts`:
- `API_BASE_URL` defaults to `http://localhost:8080` (`:1`) — **the backend is a separate origin; there is no Next.js route handler or rewrite proxy.**
- `apiFetch` always sets `credentials: "include"` (`:23`); `readXsrfCookie()` reads **`document.cookie`** (`:12-15`).

**Everything is client-side.** `apiFetch` cannot run on the server: `credentials: "include"` is a browser-fetch concept (a Node-side call would carry no `JSESSIONID` and 401), and nothing forwards cookies via `next/headers`. So **any layout that wants the user must be `"use client"` + `useEffect`** — exactly like `dashboard/page.tsx:37-42`. This is a pre-existing, documented constraint: *"`apiFetch` reads `document.cookie` for the CSRF token, so it cannot run on the server. That single constraint is why there are no server actions, no `revalidatePath`."* (`context/archive/2026-07-10-delete-analysis/research.md:222`)

**The per-page guards are inconsistent:**

| Page | Calls `me()`? | Reacts to 401? |
|---|---|---|
| `dashboard/page.tsx` | **Yes** (`:37-42`), gated behind `if (loading) return null` (`:84-86`). Then fires a **second** call, `getAnalyses()`, only once `user` is set (`:44-56`). | Yes (`:51-53`, `:73-76`) |
| `analyses/[id]/page.tsx` | No | Yes — 401 → `/login`, else "not found" (`:45-56`) |
| `analyses/new/page.tsx` | No | **No — it does not guard at all.** 401 falls into the generic `"Wystąpił nieoczekiwany błąd…"` branch (`:41-48`) |

**`src/proxy.ts` is the real guard** (Next 16 renamed `middleware.ts` → `proxy.ts`; it sits in `src/`, not `src/app/`, which is why it's easy to miss):

```ts
export function proxy(request: NextRequest) {
  const hasSession = request.cookies.has("JSESSIONID");
  if (!hasSession) return NextResponse.redirect(new URL("/login", request.url));
  return NextResponse.next();
}
export const config = { matcher: ["/dashboard/:path*", "/analyses/:path*"] };
```

It checks **cookie presence only**, not session validity — a stale `JSESSIONID` passes and is caught downstream by per-page 401 handling. **Do not add a `middleware.ts`.** The matcher is URL-based, so adding an `(app)` group requires **no change** to it. It is covered by `e2e/auth-redirect.spec.ts:9-18`.

**The fork — where does the header's user come from?**

- **Nowhere (recommended default).** The stated S-05 scope — wordmark → `/dashboard`, plus logout — needs **no `CurrentUser` at all**. `logout()` takes no args. The header becomes a `"use client"` component with zero fetching, and the whole question evaporates.
- **Layout-level `me()` + a React context.** Required *only* if the header displays the email. But `dashboard/page.tsx:37` already calls `me()`, so without rewiring the dashboard you get **two `GET /api/auth/me` per dashboard load** — and the dashboard's `me()` isn't merely a guard, it *sequences* `getAnalyses()` (`:45-47`), so removing it means untangling that gate too. Note there is **no React context anywhere in the app today** and `src/hooks/` does not exist (though `components.json` aliases it).
- **Per-page `me()`** — would add a request to both analyses pages and defeats the purpose of a layout-level header.

### 4. E2E blast radius

**Direct breaks: zero.** No spec references "Falcon", "Wyloguj", or the dashboard header card. Deleting `dashboard/page.tsx:91-106` breaks **no existing assertion** — with one exception, below. There are **no `getByRole("heading", …)` queries anywhere** in the suite, because shadcn's `CardTitle` renders a plain `<div data-slot="card-title">` (`components/ui/card.tsx:36-47`) — **the app has zero `h1`–`h6` elements today.** A new `<h1>Falcon</h1>` cannot collide with anything.

**⚠️ The one real regression, and the suite cannot see it.** `dashboard/page.tsx:99` — `<Link href="/analyses/new">Nowa analiza</Link>` — lives *inside* the card being deleted. Consumers: `e2e/fixtures.ts:38`, `e2e/analysis-input-validation.spec.ts:32,60`, `e2e/disclaimer.spec.ts:16`. **They survive by luck:** every one of those clicks happens on a *freshly registered, empty* dashboard, where the **empty-state CTA at `dashboard/page.tsx:122`** still matches. But a user **with** analyses sees only the list — so removing the card leaves them **no way to start a new analysis**, and **no spec covers that path.** The header must carry a "Nowa analiza" affordance, or the dashboard must keep one outside the deleted card. (The comment at `fixtures.ts:36-37` — "appears twice … the header action and the empty-state CTA" — becomes factually wrong either way.)

**Conditional strict-mode traps** (Playwright throws when a locator resolves to >1 element):

| If the header adds… | What breaks | Where |
|---|---|---|
| The **analysis title / breadcrumb** | `page.getByText(title)` would match the header *and* the `CardTitle` at `analyses/[id]/page.tsx:172` → **strict-mode violation** | `analysis-history.spec.ts:15`, `:23` |
| Anything whose accessible name contains **"Usuń"** | `getByRole("button", { name: "Usuń" })` is **unscoped** in 5 specs and relies on exactly one match per page (`name` is substring-matched, so it already matches the `aria-label="Usuń analizę: {title}"` at `dashboard/page.tsx:149`) | `analysis-history.spec.ts:28`, `clause-decision.spec.ts:50`, `analysis-result.spec.ts:61`, `disclaimer.spec.ts:33`, `delete-analysis.spec.ts:27` |
| A **`role="alert"`** banner | `getByRole('alert')` is *already* documented as ambiguous on `/analyses/[id]` (`disclaimer.spec.ts:8-11`) — don't make it worse | — |
| A **"Nowa analiza"** link | `.first()` still passes but silently retargets to the header's link, not the CTA | `fixtures.ts:38`, `analysis-input-validation.spec.ts:32,60`, `disclaimer.spec.ts:16` |

**Locator rule for the new header:** `getByText("Falcon")` is **unusable** — `getByText` substring-matches, and "Falcon" appears in body copy at `analyses/new/page.tsx:60`, `:104` and `analyses/[id]/page.tsx:165`. Use **`getByRole("link", { name: "Falcon" })`** and **`getByRole("button", { name: "Wyloguj" })`**. A `<header>` (implicit `role="banner"`) landmark gives specs a clean scoping handle with no `data-testid`.

**Coverage gaps this slice inherits (all net-new tests):**
- **Logout has zero E2E coverage.** `handleLogout` (`dashboard/page.tsx:58-61`) is untested today. `auth-redirect.spec.ts` never logs in — it only checks a cookie-less context is bounced.
- **Nothing asserts the header's absence** on `/login` and `/register`.
- Nothing covers "start a new analysis from a *populated* dashboard".

**Session setup is safe.** `fixtures.ts:16-28` `registerFreshUser` drives the real UI (`/register` → `getByLabel("Email")` → … → `waitForURL("/dashboard")`); the session is just the `JSESSIONID` cookie. **Nothing depends on the dashboard's DOM** — only on the URL. No `storageState`, no `globalSetup`. Every test registers its own timestamped account, so `fullyParallel: true` is safe.

**Existing locator conventions are followed strictly** — `getByRole`/`getByLabel`/`getByText` dominate; there is exactly **one** `data-testid` in the entire frontend (`analyses/[id]/page.tsx:204`, `clause-${clause.id}`, justified because the clause card has no ARIA role).

**How the suite runs:** `cd frontend && pnpm test:e2e`. `playwright.config.ts` boots **both** servers itself via a `webServer` array (`:27-72`, `reuseExistingServer: !isCI`): the backend under the `e2e` Spring profile (deterministic fixture classifications, no OpenRouter key) and the frontend. `retries: 0`, `fullyParallel: true`, `trace: "retain-on-failure"`. CI-gated in `.github/workflows/ci.yml:85-154`.

### 5. Prior art & conventions

**This slice inverts the house style, and that's the point.** Every prior slice **duplicated** UI rather than extracting it. The closest precedent — `context/archive/2026-07-10-delete-analysis/` — touched *both* the dashboard and the analysis page and deliberately did **not** extract a shared component: it shared only the *primitive* (the `alert-dialog`, added via the shadcn CLI) and the *data call* (`deleteAnalysis()` in `lib/analyses.ts`), then wrote the interaction separately on each page with different behavior (optimistic list filter vs. navigate-away). And `context/archive/2026-07-08-analysis-history/plan.md:44` explicitly **deferred** this very thing: *"No shared header/nav component — the entry point is the dashboard itself; a global nav is out of scope."*

So `AppHeader` would be the **first non-primitive component in the repo** (`src/components/` contains only `ui/` — 10 shadcn primitives). The plan should say so explicitly rather than silently break pattern, and should state where it lives (`src/components/app-header.tsx` is the natural home given the `@/components` alias in `components.json:19-25`).

**Recurring impl-review finding classes** (these *will* be applied to this slice):
1. **Ambiguous accessible names** — the single most-repeated frontend finding. S-04's F1: every row's delete button was a bare "Usuń", so a screen-reader user heard the same name for every row; fixed with a content-embedded `aria-label` (`Usuń analizę: ${title}`), matching the clause group's `aria-label` convention. *"Not caught by manual testing (single-row account)."* **Directly in this slice's blast radius** — a global header duplicating a name that already exists on a page is exactly this bug.
2. **Missing E2E cleanup** — flagged twice (analysis-history F2, testing-frontend-e2e F1). Each spec must clean up after itself.
3. **Stale comments falsified by the change itself** — delete-analysis F2/F3. The reviewer reads comments as artifacts. Removing the dashboard card falsifies `fixtures.ts:36-37`.
4. **`useEffect` without unmount/abort guards** on all three pages — analysis-history F4, **deliberately SKIPPED**: *"if this pattern is worth hardening… it should be done consistently across all three affected pages in a dedicated cleanup pass, not piecemeal."* **Resist fixing this in passing.**

**The generalized flake lesson** (commit `f4295a7`, post-archive): *wait on the network response, not a DOM proxy, whenever a test navigates right after a mutation.* `delete-analysis.spec.ts` raced because the DELETE was still in flight (status `-1`, cancelled) when `page.goto()` destroyed the context; `not.toBeVisible()` was an unreliable proxy since the dialog's close transition makes the row transiently invisible. Fixed with `Promise.all([page.waitForResponse(…204…), click()])`; verified 3/15 failures before → 0/20 after. **A logout spec must `waitForResponse` on the logout POST, not just `waitForURL("**/login")`.**

**Polish copy conventions:** hardcoded inline in JSX (only *enum label maps* are centralized, in `lib/analyses.ts` / `lib/risk.ts`) — **do not introduce i18n or a strings file.** Established strings: **`Falcon`**, **`Nowa analiza`**, **`Wyloguj`**, **`Zalogowano jako {email}`**, `Usuń`, `Anuluj`. In-progress states use a real ellipsis **`…` (U+2026)**; errors end with **"Spróbuj ponownie."** **There is no "Pulpit" anywhere** — the dashboard is never named in Polish. The roadmap's wording is *"the 'Falcon' wordmark links to `/dashboard`"*, i.e. **the wordmark is the affordance**; inventing "Pulpit" would be a new-word decision that also becomes an e2e locator, so flag it if the plan wants it.

**House plan format** (from `context/archive/2026-07-10-delete-analysis/plan.md`): `## Overview` → `## Current State Analysis` (every claim carries file:line) → `## Desired End State` (+ **Verification**) → `## What We're NOT Doing` → `## Implementation Approach` → `## Critical Implementation Details` → `## Phase N` (each with `### Changes Required` as **File / Intent / Contract**, `### Success Criteria` split **Automated / Manual**, and a closing *"pause here for manual confirmation from the human before proceeding"*) → `## Testing Strategy` → `## References` → `## Progress` (`- [ ]`/`- [x]`, ` — <commit sha>` appended when a step lands; numbering continuous within a phase across the Automated/Manual split).

### 6. Visual/design direction

**Theme** (`src/app/globals.css`, 139 lines): Tailwind 4 CSS-first — `@import "tailwindcss"` + `tw-animate-css`, `@custom-variant dark (&:is(.dark *))` (`:5`), an `@theme inline` block (`:7-49`) mapping semantic colors, and OKLCH tokens (`:51-118`). The palette is **strictly grayscale** (all chroma 0) except `--destructive`; the only non-neutral color in the app is `RISK_LEVEL_BADGE_CLASS` in `lib/risk.ts:14-18`. `--radius: 0.625rem`. Base layer: `* { @apply border-border outline-ring/50 }`, `body { @apply bg-background text-foreground }`, `html { @apply font-sans }` (`:120-130`).

**Dark mode is wired but dead** — no `.dark` class is ever applied (no theme provider). Use semantic tokens anyway so it works if enabled.

**⚠️ `--font-sans` is broken.** `@theme` declares `--font-sans: var(--font-sans)` (`globals.css:10`) — self-referential — while `layout.tsx:5-13` exposes `--font-geist-sans`. So `font-sans`/`font-heading` resolve to an undefined var and fall back to Tailwind's default stack; only `font-mono` is correctly wired. **Don't rely on `--font-sans` in the header** — use weight utilities (`font-medium`/`font-semibold`) like the rest of the app. (Also: `globals.css:3` imports `shadcn/tailwind.css`, which does not exist on disk in `node_modules/shadcn/`.)

**Type scale in use:** card body base `text-sm`; `CardTitle` = `font-heading text-base leading-snug font-medium` (`card.tsx:41`); meta `text-xs text-muted-foreground`; errors `text-sm`/`text-xs text-destructive`; inline link `text-primary underline-offset-4 hover:underline`.

**Buttons** (`components/ui/button.tsx:7-42`) — a **compact** set: default height is `h-8` (32px), `sm` is `h-7`. Variants: `default`, `outline`, `secondary`, `ghost`, `destructive` (**tinted, not solid**: `bg-destructive/10 text-destructive`), `link`. Base includes `rounded-lg text-sm font-medium focus-visible:ring-3 focus-visible:ring-ring/50` and auto-sizes bare `svg` children to `size-4`. **`asChild` is supported and already used for nav links** — `dashboard/page.tsx:98-100` wraps `<Link href="/analyses/new">` in `<Button asChild>`; the same pattern works for a wordmark-as-link.

**shadcn setup** (`components.json`): style `radix-nova`, base color `neutral`, `cssVariables: true`, icon library `lucide`, RSC on. **10 primitives installed**: alert, alert-dialog, badge, button, card, input, label, separator, skeleton, textarea. **Nav-ish primitives are absent** — `navigation-menu`, `dropdown-menu`, `avatar`, `tooltip`, `sheet` would need `npx shadcn@latest add`. For wordmark + one button, **none are required**: a plain `<header>` + `Button` + `next/link` matches the existing idiom with zero new deps. Add primitives **via the CLI only** — never hand-author into `components/ui/` (delete-analysis's plan states this explicitly).

**Icons:** `lucide-react` is a dependency but **exactly one icon is used in the whole app** — `Loader2` at `analyses/new/page.tsx:5,92`. A `<LogOut />` in a `size="sm"` button needs no size class (auto-sized).

**The chrome being consolidated** — `dashboard/page.tsx:91-106`, verbatim:

```tsx
 91        <Card>
 92          <CardHeader>
 93            <CardTitle>Falcon</CardTitle>
 94          </CardHeader>
 95          <CardContent className="flex flex-col gap-4">
 96            <p className="text-sm text-foreground">Zalogowano jako {user?.email}</p>
 97            <div className="flex gap-2">
 98              <Button asChild>
 99                <Link href="/analyses/new">Nowa analiza</Link>
100              </Button>
101              <Button variant="outline" onClick={handleLogout}>
102                Wyloguj
103              </Button>
104            </div>
105          </CardContent>
106        </Card>
```

Semantics to preserve: wordmark **"Falcon"**; identity line **"Zalogowano jako {email}"**; primary CTA **"Nowa analiza"** → `/analyses/new`; secondary **"Wyloguj"** as `variant="outline"`.

**Patterns to mirror:** "title left / action right" already exists as `CardAction` (`analyses/[id]/page.tsx:173-193`, a right-aligned grid cell). Focus ring on non-button interactive elements: `rounded outline-none focus-visible:ring-3 focus-visible:ring-ring/50` (`dashboard/page.tsx:136`) — reuse for a wordmark `<Link>` that isn't a `Button`. Cards use a **ring** (`ring-1 ring-foreground/10`), not a border; a header with `border-b border-border` would be new-but-consistent (same OKLCH value).

## Code References

- `frontend/src/app/layout.tsx:20-33` — the only layout; `body` is the `flex flex-col` container the whole height chain depends on
- `frontend/src/app/layout.tsx:15-18` — stale `"Create Next App"` metadata, `lang="en"`
- `frontend/src/proxy.ts:1-14` — the real auth guard (Next 16's renamed middleware); URL matcher, cookie-presence check only
- `frontend/src/lib/auth.ts:30-38` — `logout()` (no args, no navigation) and `me()`
- `frontend/src/lib/api.ts:12-23` — `document.cookie` + `credentials: "include"` → the client-only constraint
- `frontend/src/app/dashboard/page.tsx:37-56` — `me()` guard that *sequences* `getAnalyses()`
- `frontend/src/app/dashboard/page.tsx:91-106` — the chrome being consolidated into the header
- `frontend/src/app/dashboard/page.tsx:122` — the empty-state "Nowa analiza" CTA that masks the regression
- `frontend/src/app/analyses/new/page.tsx:55` / `(auth)/login/page.tsx:42` — the `items-center` containers whose centering shifts under a header
- `frontend/src/app/analyses/[id]/page.tsx:204` — the app's only `data-testid`
- `frontend/src/components/ui/button.tsx:7-42` — variants/sizes; `asChild` for nav links
- `frontend/src/components/ui/card.tsx:36-47` — `CardTitle` renders a `<div>`, not a heading (hence zero `h1`–`h6` in the app)
- `frontend/src/app/globals.css:7-49` — `@theme` block; `:10` the broken self-referential `--font-sans`
- `frontend/e2e/fixtures.ts:16-28,35-46` — `registerFreshUser` / `seedAnalysis`; `:36-38` the `.first()` workaround
- `frontend/playwright.config.ts:27-72` — the two-server `webServer` array

## Architecture Insights

- **The app is a client-side SPA in App Router clothing.** Five pages, five `"use client"` directives, one server layout that renders nothing but fonts. There are **no server components doing work, no server actions, no providers, no context, no hooks directory** — a single constraint (`apiFetch` reads `document.cookie`) forced all of it, and it propagates directly into this slice: the header must be a client component.
- **`flex-1` on every page root is an undocumented layout contract with the root layout's `body`.** It works only because no intermediate layout exists. This slice is the first to insert one — which makes "Fragment, not `<div>`" the single highest-leverage implementation detail in the change.
- **Accessibility naming is where this codebase's UI bugs live.** Two of the last three impl-reviews flagged ambiguous accessible names, and both were invisible to manual testing. A component that appears on *every* page is a name-collision machine; the header's names must be checked against every page it lands on, not just the dashboard.
- **The E2E suite's green is not evidence of correctness here.** It authenticates by driving the real registration UI onto an *empty* dashboard, so the entire "returning user with saved analyses" surface — precisely the one this change regresses — is untested. This is the rare case where the tests would happily ship the bug.

## Historical Context (from prior changes)

- `context/archive/2026-07-08-analysis-history/plan.md:44` — explicitly deferred this slice: *"No shared header/nav component — the entry point is the dashboard itself; a global nav is out of scope."* S-05 redeems that deferral.
- `context/archive/2026-07-10-delete-analysis/plan.md` — the closest structural precedent (a cross-screen UI change) and the house plan template. Phase order, "pause for manual confirmation" gates, `## Progress` format.
- `context/archive/2026-07-10-delete-analysis/plan.md:237` — shadcn primitives are added **via the CLI only**, never hand-authored.
- `context/archive/2026-07-10-delete-analysis/research.md:222` — the `document.cookie` constraint that makes the app all-client.
- `context/archive/2026-07-10-delete-analysis/reviews/impl-review.md` — F1, the content-embedded `aria-label` convention.
- `context/archive/2026-07-10-testing-frontend-e2e/` — the E2E conventions, the `e2e` Spring profile, and the grep gates (`locator\(['"][.#]` and `waitForTimeout` must both return nothing).
- `context/foundation/test-plan.md` §6.6 — the "adding a browser E2E test" cookbook; read before writing any spec.
- Commit `f4295a7` — the DELETE-response race and the standalone-server fix; source of the *"wait on the response, not a DOM proxy"* rule.

## Related Research

- `context/archive/2026-07-10-delete-analysis/research.md` — frontend data/mutation patterns, the client-only constraint
- `context/archive/2026-07-10-testing-frontend-e2e/research.md` — Playwright suite design
- `context/archive/2026-07-08-analysis-history/research.md` — the dashboard's construction

## Open Questions

These are **scope decisions for the plan**, not unknowns in the code:

1. **Does the header carry "Nowa analiza"?** ← *the one that matters.* The roadmap says "keep the header minimal (wordmark → dashboard, logout)", but removing the dashboard card without a replacement leaves a user **with** analyses no way to start a new one, and **the E2E suite will not catch it**. Either the header gets the CTA, or the dashboard keeps one outside the deleted card. This must be decided explicitly.
2. **Does the header show "Zalogowano jako {email}"?** If **no**, the header needs zero data fetching and the `me()`/context question disappears entirely (recommended). If **yes**, it needs a layout-level `me()` + a React context (the app's first), and the dashboard's own `me()` must be rewired to avoid a duplicate request and to keep sequencing `getAnalyses()`.
3. **`(app)` route group (file moves, one persistent layout) vs. two sibling layouts (no moves, header remounts across `/dashboard` ↔ `/analyses/*`)?** The research favors the route group; the roadmap already names it.
4. **What about `/`?** It is still the untouched create-next-app template. A "Falcon" wordmark reads as "home", but `/` is a Vercel starter page. Out of S-05's stated scope — but worth a one-line redirect decision, or an explicit deferral.
5. **New Polish word?** There is no "Pulpit" in the app. If the header wants a labelled dashboard link rather than a bare wordmark, that's a new copy string *and* a new e2e locator.

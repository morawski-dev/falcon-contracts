# App Navigation Header (S-05) — Plan Brief

> Full plan: `context/changes/app-navigation-header/plan.md`
> Research: `context/changes/app-navigation-header/research.md`

## What & Why

Falcon's navigation graph is one-directional. The dashboard links out to the analyze screens, but neither `/analyses/new` nor `/analyses/[id]` offers any way back — a user who finishes reading an analysis is stranded on the browser back button. This slice adds a persistent authenticated app header so the dashboard is reachable from anywhere.

## Starting Point

The app has exactly **one layout** (`frontend/src/app/layout.tsx`), whose `<body className="min-h-full flex flex-col">` is a column flex container that every page's `flex-1` root depends on. There is no authenticated shell, no shared non-primitive component, and no React context anywhere. The dashboard renders its own card carrying the "Falcon" wordmark, the user's email, a "Nowa analiza" link, and "Wyloguj". The real auth guard is `src/proxy.ts` (Next 16's renamed middleware), which checks only that a `JSESSIONID` cookie exists.

## Desired End State

A logged-in user on any authenticated screen sees a sticky header: the Falcon wordmark (→ `/dashboard`), "Nowa analiza", and "Wyloguj". They can return to the dashboard, start a new analysis, or log out from anywhere — including from the bottom of a long analysis. `/login` and `/register` show no header, and `/` no longer shows the Next.js starter page.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Shell structure | `(app)` route group with a Fragment-returning layout | The only option giving one *persistent* layout instance across all three routes; a Fragment emits no DOM node, so every page's `flex-1` keeps working with zero CSS changes. | Research |
| "Nowa analiza" affordance | Lives in the header | Deleting the dashboard card removes the only CTA a user *with* analyses can see — and the E2E suite is blind to it. Putting it in the header fixes the regression at source and makes it reachable from the result page too. | Plan |
| User email in header | Dropped entirely | Removing "Zalogowano jako" means the header fetches **nothing** — no `me()`, no React context, no `src/hooks/`. Eliminates a whole class of complexity for a small feature loss. | Plan |
| Dashboard's `me()` call | Removed | With the email gone it is orphaned — its only jobs were rendering the email and gating `getAnalyses()`, and `proxy.ts` plus the 401 handler already guard the route. | Plan |
| Header behavior | Sticky, bordered, aligned to the `max-w-2xl` column | `/analyses[id]` is the app's longest screen — the very one this slice exists to fix — so nav must stay reachable without scrolling to the top. | Plan |
| Sticky side-effects | Guard both in CSS (`scrollbar-gutter: stable`, `scroll-padding-top`) | Sticky isn't just a z-index question: Radix's scroll-lock shifts the header sideways when a dialog opens, and Playwright's auto-scroll parks click targets underneath it (a latent CI flake). Two one-line guards kill both classes. | Plan review |
| Empty-state CTA | Removed — empty state is text-only | Keeping it alongside the header's CTA would put two controls with an identical accessible name *and* destination on one page — the exact collision class the plan forbids. It also lets all four `.first()` disambiguations drop out of the E2E suite. | Plan review |
| Root page `/` | Redirect to `/dashboard`, and delete the 5 orphaned template SVGs | A "Falcon" wordmark reads as *home*, and home is currently a Vercel starter template. The SVGs' only consumer is the page being replaced; they'd otherwise still ship in the standalone image. | Plan / Plan review |
| E2E scope | All four surfaces | Logout has *zero* coverage today, and no spec can currently see the "new analysis from a populated dashboard" regression. | Plan |

## Scope

**In scope:** the `(app)` route group and its layout; `AppHeader` (the repo's first non-primitive component); deleting the dashboard's chrome card and its orphaned `me()`; a root redirect plus the stale `"Create Next App"` metadata and `lang="en"`; four new E2E tests.

**Out of scope:** the user's email in the header; React context / `src/hooks/` / providers; any nav menu, breadcrumb, sidebar, dropdown or avatar; **any backend change**; a real landing page at `/`; `useEffect` unmount/abort hardening (deliberately deferred by `analysis-history`'s impl-review as a separate cleanup pass); i18n; inventing a Polish word for "dashboard" — the wordmark *is* the affordance.

## Architecture / Approach

`git mv` `dashboard/` and `analyses/` under a new `(app)/` route group — parenthesized, so **URLs are unchanged** and `proxy.ts`'s URL-based matcher needs no edit. `(app)/layout.tsx` is a **server component** returning `<><AppHeader />{children}</>`; only `AppHeader` is `"use client"`, because only logout touches the browser. The header fetches nothing and takes no props. The Fragment is the load-bearing detail: it emits no DOM node, so `<header>` and each page's `flex-1` root remain siblings in the body's flex column.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Authenticated shell | `(app)` route group, layout, `AppHeader` — lands **alone** against the green suite | The `git mv` is the riskiest mechanical step; and a `<div>` instead of a Fragment silently breaks every page's height chain |
| 2. Shed dashboard chrome | Delete the card, the orphaned `me()`, `user`/`loading` state | Must land *after* the header's CTA exists, or a populated dashboard briefly has no way to start an analysis |
| 3. Fix the root dead end | `/` → `/dashboard`; correct `"Create Next App"` title and `lang="en"` | Disclosed scope extras — written into the plan rather than smuggled into a commit message |
| 4. E2E coverage | 5 tests: nav from both analyze screens, logout, new-analysis-from-populated-dashboard, header absent on auth pages | The logout test is a mutation-then-navigate race — must `waitForResponse`, the exact bug that made `delete-analysis` flaky (`f4295a7`) |

**Prerequisites:** none — S-01 and S-03 are `done`; the suite is currently green.
**Estimated effort:** ~1–2 sessions across 4 phases. Frontend-only; no backend, schema, or API surface.

## Open Risks & Assumptions

- **This slice deliberately inverts the house pattern.** Every prior cross-screen change *duplicated* UI rather than extracting it, and `analysis-history/plan.md:44` explicitly deferred a shared nav component. `AppHeader` is the repo's first — intentional, and stated rather than silent.
- **Accessible-name collisions are this codebase's recurring bug class** (two of the last three impl-reviews). A component on *every* page is a collision machine: the header must introduce no control named "Usuń" (five specs use an unscoped `getByRole("button", {name: "Usuń"})`), must not render the analysis title, and must not use `role="alert"`.
- **`sticky` is the app's first positioning/z-index treatment.** Stacking is verified (dialog `z-50` vs header `z-10`), and the two non-obvious side-effects — Radix's scroll-lock shifting the header sideways, and Playwright's auto-scroll parking targets under it — are guarded in CSS. The scroll-lock jump is still confirmed by hand in Phase 1.
- **Dropping the email is a real, if small, feature loss.** Accepted in exchange for a data-free header.
- **Removing the empty-state CTA rests on an unvalidated UX judgment** — a first-time user's discoverability now depends entirely on the header. Nobody has watched a first-run user hunt for it.

## Success Criteria (Summary)

- From the bottom of a long analysis, a user can reach their dashboard in one click.
- A user *with* saved analyses can start a new one — the regression the current test suite cannot see.
- Logout works from every authenticated screen, and no header ever appears on `/login` or `/register`.

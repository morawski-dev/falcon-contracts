# Analysis closing action — Plan Brief

> Full plan: `context/changes/analysis-closing-action/plan.md`
> Roadmap slice: `context/foundation/roadmap.md` → S-07

## What & Why

After creating an analysis, returning to the dashboard is not intuitive. The cause is **not a missing link** — it is a missing *affordance*: the sticky header's `Falcon` wordmark already links to `/dashboard` but reads as branding, and the report column simply stops after the last clause, offering nothing at the moment the user has finished reading and asks "what now". This is worst right after creation, because the user arrives by a redirect and has no mental model of where they are.

## Starting Point

S-05 shipped a persistent header whose wordmark links to `/dashboard` — and `frontend/e2e/app-header.spec.ts:4-8` records that as the deliberate design: *"the persistent AppHeader is the only way back to the dashboard from the analyze screens."* It works, and it is invisible. Meanwhile `analyses/[id]/page.tsx` renders one long clause column that ends in nothing.

## Desired End State

The header carries an explicit `Moje analizy` link next to the wordmark, and the report closes with a `Wróć do moich analiz` link. The route back is named, not implied — both from the top of the screen and at the point where reading ends.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) |
| --- | --- | --- |
| Diagnosis | Affordance, not navigation | The link exists and is always visible; a second unlabelled link would have changed nothing. |
| Report footer | A single `Wróć do moich analiz` link | Exactly the asked-for fix; `Nowa analiza` already lives in the header, so a second CTA would be redundant. |
| Header | Add an explicit `Moje analizy` link | Fixes the root cause for users who never scroll to the end — accepted at the cost of a fourth header element. |
| Link naming | **Two different labels** | Both links target `/dashboard`; sharing an accessible name would make `getByRole` strict-mode-ambiguous and break the suite. |
| Scope | Report screen only | `/analyses/new` has a submit action and is not a dead end. |
| Navigation mechanism | `next/link` to `/dashboard`, never `router.back()` | After the post-creation redirect, `back()` would return the user to the *form* — the exact trap being closed. |
| Tests | New `closing-action.spec.ts` + extend `app-header.spec.ts` | Every header affordance gets a dedicated guard — the lesson S-05 already encoded for `Nowa analiza`. |

## Scope

**In scope:** an explicit `Moje analizy` link in `app-header.tsx`; a `Wróć do moich analiz` link closing `analyses/[id]/page.tsx`; one new E2E spec; one extended E2E spec.

**Out of scope:** renaming or removing the `Falcon` wordmark; a closing action on `/analyses/new`; an exit from the "Nie znaleziono analizy" state; breadcrumbs, sidebar, sticky bottom bar; any backend, schema, or owner-scoping change.

## Architecture / Approach

Two thin frontend phases. Both new affordances are plain `next/link` elements pointing at `/dashboard`, styled inside S-06's existing visual system. The sequencing is deliberate: Phase 1 introduces the new accessible name into the header *first*, against a suite that does not yet contain a second dashboard link, so any locator disturbance surfaces in isolation. Phase 2 then adds the report's link under a different label.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Header | `Moje analizy` link + regression guard in `app-header.spec.ts` | Header drifting into a nav menu — S-05 explicitly forbids one |
| 2. Report | `Wróć do moich analiz` closing the report + `closing-action.spec.ts` | Accessible-name collision with the header link (mitigated by distinct labels) |

**Prerequisites:** S-05 and S-06 are done and archived — the header, the `(app)` route group, and the design system all exist.
**Estimated effort:** ~1 session; two small frontend diffs plus two E2E specs.

## Open Risks & Assumptions

- **The suite is the safety net and also the tripwire.** `CLAUDE.md` mandates accessibility-first locators, so every accessible name is a test contract. Playwright matches names as a case-insensitive *substring*, so a careless label ("Falcon — moje analizy") would collide with the four existing `name: "Falcon"` assertions. The plan avoids this; an implementer who "harmonizes" the two labels into one would reintroduce it.
- **The header now holds four elements** (`Falcon` / `Moje analizy` / `Nowa analiza` / `Wyloguj`). This is a knowing exception to S-05's "resist a full nav menu". Keep `Moje analizy` visually quiet so `Nowa analiza` stays the primary action.

## Success Criteria (Summary)

- After creating an analysis, a user reaching the end of the report sees an obvious, labelled way back to their analyses — and takes it without scrolling back up to hunt for a logo.
- From any authenticated screen, the header names the destination rather than implying it.
- `pnpm lint`, `pnpm build`, and the full `pnpm test:e2e` suite are green, with the new spec failing if the closing link is removed.

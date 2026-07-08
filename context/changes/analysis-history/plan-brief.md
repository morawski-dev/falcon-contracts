# Analysis History (S-03) — Plan Brief

> Full plan: `context/changes/analysis-history/plan.md`
> Research: `context/changes/analysis-history/research.md`

## What & Why

Roadmap slice S-03 (FR-009, the Secondary success signal): let a returning user see a list of their past analyses and reopen any one — strictly owner-scoped. It builds directly on S-01, whose "reopen one analysis" path already ships; this slice adds the missing *list*.

## Starting Point

The single-analysis path is complete: `GET /api/analyses/{id}` (owner-scoped, 404-not-403) and the `/analyses/[id]` result view were designed at S-01 as S-03's reopen target. What's absent is symmetric — no list-by-owner repository query, no `GET /api/analyses`, no summary DTO, no list UI, and (per an `AnalysisIsolationTest` code comment) no list-isolation test. The frontend `/dashboard` was pre-designed as the list's home. No frontend test tooling exists yet.

## Desired End State

A logged-in user opens `/dashboard` and sees their analyses newest-first (title, status, date); clicking one reopens the full breakdown. A user with none sees a Polish empty-state CTA to start a new analysis. No user can ever see another's rows — proven by a backend integration test and a browser E2E test.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| List payload | Minimal summary (id/title/status/createdAt) via JPQL projection | Avoids hydrating every clause per row; no transaction needed | Plan |
| List home | Fold into `/dashboard` | Dashboard was pre-designed for this; one fewer route | Plan |
| Pagination | None — plain list, `createdAt DESC` | Matches PRD small/low-QPS scale; simplest everything | Plan |
| Empty state | Polish CTA → `/analyses/new` | Turns a dead-end into an onboarding nudge | Plan |
| Test depth | Backend integration **+ first Playwright E2E** | Guardrail where enforced, plus browser confidence for the UI | Plan |
| Owner-scoping | Query-layer filter, never `findAll` + filter | Established convention (`SecurityUtils`); a leak is a guardrail regression | Research |

## Scope

**In scope:** `GET /api/analyses` (owner-scoped summary list) + index migration; dashboard list UI + empty state; list-never-leaks + repository + auth-matrix tests; first Playwright harness + history E2E.

**Out of scope:** new `/analyses` list route; pagination; clause counts / risk badges on rows; delete (S-04); any change to the single-get endpoint, result view, or security config.

## Architecture / Approach

Back-to-front vertical: migration index on `analyses(owner_id, created_at)` → repository JPQL projection into a new `AnalysisSummaryResponse` → `listAnalyses(ownerId)` service → `GET /api/analyses` controller (reusing `@AuthenticationPrincipal` + `.anyRequest().authenticated()` gating) → dashboard `getAnalyses()` + `Card`/`Link` rows → Playwright test driving login → list → reopen.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Backend endpoint + guardrail tests | Owner-scoped `GET /api/analyses` + list-never-leaks test | Forgetting the owner filter (cross-user leak) — mitigated by the isolation test |
| 2. Dashboard history list | List folded into `/dashboard` + Polish empty state | 401/loading/empty edge states on the client |
| 3. First Playwright E2E | Repo's first browser test of list + reopen | New tooling; flakiness if waits aren't state-based |

**Prerequisites:** S-01 (`analyze-and-save-contract`) done — it is. Local run needs Postgres (auto via `spring-boot:run`) + `pnpm dev`.
**Estimated effort:** ~2–3 sessions across the 3 phases; Phase 3 (Playwright bootstrap) is the largest unknown.

## Open Risks & Assumptions

- Playwright is new to this repo — Phase 3 carries first-time setup cost (browser install, deciding whether the config boots the backend too).
- The E2E test needs a real session + seeded analyses; whether to seed via the UI (paste flow) or a fixture is decided in implementation.
- JPQL constructor-expression projection assumes the summary DTO stays a top-level record.

## Success Criteria (Summary)

- A returning user sees their own analyses on `/dashboard` newest-first and can reopen any one.
- No user can see another user's analyses — enforced at the query layer and proven by test.
- A first-time user gets a Polish empty-state CTA instead of a blank page.

---
date: 2026-07-08T00:00:00+02:00
researcher: Mateusz Morawski
git_commit: 48a46d07ddcbf98a3ae2015bed61b9fa60b1dbc4
branch: main
repository: morawski-dev/falcon-contracts
topic: "Analysis history (S-03): reuse surfaces for a list-my-analyses + reopen feature"
tags: [research, codebase, analysis-history, owner-scoping, s-03, reuse-surface]
status: complete
last_updated: 2026-07-08
last_updated_by: Mateusz Morawski
---

# Research: Analysis history (S-03)

**Date**: 2026-07-08T00:00:00+02:00
**Researcher**: Mateusz Morawski
**Git Commit**: 48a46d07ddcbf98a3ae2015bed61b9fa60b1dbc4
**Branch**: main
**Repository**: morawski-dev/falcon-contracts

## Research Question

For roadmap slice **S-03 (`analysis-history`)** — "a returning user sees a list of their past analyses and can reopen any one, strictly owner-scoped" (FR-009, the Secondary success signal) — map the existing seams built by S-01 (`analyze-and-save-contract`) and F-01 (`identity-and-isolation`) that this slice must plug into, so planning can distinguish *reuse* from *net-new* and avoid re-introducing a cross-user leak.

## Summary

**This is a small, well-bounded vertical add, not a from-scratch feature.** The "reopen one analysis" half of S-03 **already ships end-to-end** — `GET /api/analyses/{id}` (owner-scoped, 404-not-403) and the frontend result view at `/analyses/[id]` both exist and were deliberately designed at S-01 to be S-03's reopen target. What is genuinely missing is the **list** half, and it is missing symmetrically across all four layers:

| Layer | Reopen-one (exists) | List (must add) |
|---|---|---|
| Repository | `findByIdAndOwnerId(id, ownerId)` | `findByOwnerIdOrderByCreatedAtDesc(ownerId)` — **absent** |
| Service | `getAnalysis(id, ownerId)` | `listAnalyses(ownerId)` — **absent** |
| Controller | `GET /api/analyses/{id}` | `GET /api/analyses` — **absent** |
| DTO | `AnalysisResponse` (heavy, full clauses) | `AnalysisSummaryResponse` (id/title/status/createdAt) — **absent** |
| Frontend route | `app/analyses/[id]/page.tsx` | `app/analyses/page.tsx` (list) — **absent** |
| Frontend client | `getAnalysis(id)` | `getAnalyses()` — **absent** |
| Test | 3-layer owner-isolation for single-get | **list-never-leaks** test — **explicitly deferred to S-03** |

The **load-bearing risk** the roadmap names ("the list query must be owner-scoped; a leak is a guardrail regression") has a concrete, codified antidote already in the repo: an owner-scoping query convention (`SecurityUtils` Javadoc), a 404-no-existence-leak pattern, and a three-layer isolation test template — all reusable. The one deliberately-deferred test (list-never-leaks) is called out **verbatim in the code** as S-03's job.

Everything needed is unblocked: security auto-gates the new GET route (`.anyRequest().authenticated()`), CORS already allows GET, and CSRF is not required for GET.

## Detailed Findings

### Backend persistence & owner-scoping

- **`Analysis` entity** ([Analysis.java:19-57](https://github.com/morawski-dev/falcon-contracts/blob/48a46d07ddcbf98a3ae2015bed61b9fa60b1dbc4/backend/src/main/java/com/morawski/dev/falcon/analysis/Analysis.java#L19-L57)) — table `analyses`, `Long id` (IDENTITY), `ownerId` as a plain scalar `Long` (raw FK, `owner_id` not null), `title` varchar(200), `rawText` varchar(20000), `status` (`@Enumerated(STRING)`), and crucially **`createdAt` (`Instant`, `created_at` not null)** — the sortable ordering key for a history list already exists and is populated. No `updatedAt`. No public setters (read-only after construction). `@OneToMany` LAZY collections for `clauses` and `negotiationPoints`.
- **Status enum** `AnalysisStatus { DRAFT, ANALYZED, REVIEWED }` — new analyses persist as `ANALYZED` ([AnalysisService.java:44](https://github.com/morawski-dev/falcon-contracts/blob/48a46d07ddcbf98a3ae2015bed61b9fa60b1dbc4/backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisService.java#L44)); `DRAFT`/`REVIEWED` are defined but currently unused.
- **`AnalysisRepository`** ([AnalysisRepository.java:9](https://github.com/morawski-dev/falcon-contracts/blob/48a46d07ddcbf98a3ae2015bed61b9fa60b1dbc4/backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisRepository.java#L9)) — exactly one custom method: `Optional<Analysis> findByIdAndOwnerId(Long id, Long ownerId)`. Inherited `findAll()` is **not** owner-scoped and must never be used for the list. **GAP:** no `findByOwnerId...` list query.
- **Service read path** `getAnalysis(id, ownerId)` ([AnalysisService.java:64-72](https://github.com/morawski-dev/falcon-contracts/blob/48a46d07ddcbf98a3ae2015bed61b9fa60b1dbc4/backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisService.java#L64-L72)) — `findByIdAndOwnerId(...).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND))`, `@Transactional(readOnly = true)`. Enforces ownership **at the query level** and returns **404 (not 403)** so existence isn't leaked.
- **Migrations** — `002-create-analyses.yaml` creates `analyses`/`clauses`/`negotiation_points` with `created_at timestamptz not null` and FKs (`fk_analyses_owner`, cascade deletes on children). **GAP:** no index on `analyses.owner_id` or `created_at` — the history query (filter by owner, order by createdAt) will full-scan; a new changeset should add an index on `(owner_id, created_at)`.

### Backend API surface & current-user resolution

- **`AnalysisController`** ([AnalysisController.java:27-37](https://github.com/morawski-dev/falcon-contracts/blob/48a46d07ddcbf98a3ae2015bed61b9fa60b1dbc4/backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisController.java#L27-L37)) exposes only `POST /api/analyses` (201) and `GET /api/analyses/{id}` (200 / 404). **GAP: no `GET /api/analyses` list endpoint.**
- **Current-user resolution to copy** — `@AuthenticationPrincipal AppUserDetails principal` in the controller method, passing `principal.getId()` (a `Long`) to the service. There is also a `SecurityUtils.currentUserId()` helper whose Javadoc codifies the convention: *"Every repository method returning owner-scoped data takes an ownerId parameter; callers pass currentUserId() — there is no bare findById for owned entities."* The controllers use the `@AuthenticationPrincipal` form — copy that for consistency.
- **DTOs** ([AnalysisResponse.java:8-15](https://github.com/morawski-dev/falcon-contracts/blob/48a46d07ddcbf98a3ae2015bed61b9fa60b1dbc4/backend/src/main/java/com/morawski/dev/falcon/analysis/dto/AnalysisResponse.java#L8-L15)) — `AnalysisResponse(id, title, status, createdAt, List<ClauseResponse>, List<NegotiationPointResponse>)` is the only analysis DTO. It already carries the four list-row fields but embeds all clause text. **GAP: no lightweight summary DTO** — add `AnalysisSummaryResponse(id, title, status, createdAt)` so a list row doesn't hydrate every clause.
- **Security** ([SecurityConfig.java:31-52](https://github.com/morawski-dev/falcon-contracts/blob/48a46d07ddcbf98a3ae2015bed61b9fa60b1dbc4/backend/src/main/java/com/morawski/dev/falcon/auth/SecurityConfig.java#L31-L52)) — session-cookie auth (JSESSIONID, **not** JWT), `csrf.spa()` (cookie/header, GET exempt), CORS allows origin `localhost:3000` + methods `GET, POST`, and `.anyRequest().authenticated()`. **A new `GET /api/analyses` is auto-protected with zero config change; no CORS/CSRF change needed for a GET.** Unauthenticated → 401.

### Frontend view, routing & client

- **App Router** — the analysis feature is fully built (not a bare starter). Existing routes: `/login`, `/register` (route-group `(auth)`), `/dashboard` (the intended home for a history link), `/analyses/new` (paste form), and **`/analyses/[id]`** (the result view). **GAP: no `/analyses` index route** — a history list is a new `app/analyses/page.tsx`.
- **Result view** `app/analyses/[id]/page.tsx` keys entirely off the URL `id` — **a history row linking to `/analyses/{id}` reopens the exact same view with zero changes** to that component.
- **API client** `frontend/src/lib/api.ts` — `apiFetch` sets `credentials: "include"` (sends session cookie), auto-attaches `X-XSRF-TOKEN` on mutations, throws `ApiError`. Domain client `frontend/src/lib/analyses.ts` has `createAnalysis(...)` and `getAnalysis(id)`. **GAP: no `getAnalyses()` list call** — add it (mirrors `getAnalysis`, no CSRF since GET). All callers are **client components** (`"use client"`); the wrapper reads `document.cookie`, so no server-component fetching exists.
- **Auth/session** — route protection is `frontend/src/proxy.ts` (Next.js 16's renamed `middleware.ts`), matcher `["/dashboard/:path*", "/analyses/:path*"]` → **any new `/analyses` page is already auth-gated** by the JSESSIONID cookie check. Client identity via `me()` → `GET /api/auth/me`.
- **UI shell & primitives** — **no nav/header exists** (root layout is bare); each page centers a card. Installed shadcn/ui: `button, input, label, card, textarea, badge, alert, separator, skeleton` (no `table`, no `dropdown-menu`). A history list is best built from existing **`Card` rows wrapped in `Link`** (the app's established idiom), no new dependency. Add the entry-point link on `dashboard/page.tsx`.
- **Types** — `frontend/src/lib/analyses.ts` mirrors the backend: `Analysis { id, title, status, createdAt, clauses[], negotiationPoints[] }`; risk label/badge maps in `frontend/src/lib/risk.ts`. **GAP: no `AnalysisSummary` type** — add if the list endpoint returns summaries.

### Test patterns & the deferred isolation test

- **LLM mock (the critical pattern)** — a real lambda `@Bean @Primary ChatModel` returning a fixed `ChatResponse`, **never a Mockito mock** (an unstubbed `ChatModel.getOptions()` NPEs). See `AnalysisFlowTest.MockChatModelConfig` and the dummy `spring.ai.openai.api-key=test` in test properties. A history-list test needs **no LLM mock at all** — it seeds analyses directly via the repository (as `AnalysisIsolationTest` does).
- **Testcontainers** — `TestcontainersConfiguration` (`@ServiceConnection PostgreSQLContainer("postgres:18")`), imported by every `@SpringBootTest`. Tests use `@SpringBootTest @AutoConfigureMockMvc`, **not** `@DataJpaTest`, and do explicit `@AfterEach deleteAll()` (not auto-rolled-back).
- **Three-layer owner-isolation model (the template to replicate):** repository-level (`AnalysisRepositoryTest.findByIdAndOwnerIdScopesToOwner`), HTTP e2e cross-user 404 (`AnalysisFlowTest.crossUserCannotReadAnotherUsersAnalysis`), and byte-identical no-existence-leak 404 (`AnalysisIsolationTest`).
- **The deferred test is named in code:** `AnalysisIsolationTest` comments state *"List-isolation … is deferred to S-03 — there is no list endpoint yet."* **Filling the list-never-leaks test (seed A's + B's analyses, GET list as B, assert A's rows absent) is S-03's explicit test obligation.**
- **Auth idiom** — `persistUser` helper + `.with(csrf()).with(user(new AppUserDetails(owner)))`; GETs drop `.with(csrf())`. No `@WithMockUser`.
- **Ordering caveat** — `Analysis.clauses` has no `@OrderBy`, so tests look items up by attribute, not index. A history list's `createdAt DESC` order must be **explicit** (query `Sort`/`OrderBy`) and its test must assert order explicitly.
- **Frontend tests: none exist** — no `playwright.config.ts`, no test runner in `package.json`. A history-list browser test would be the repo's first Playwright setup (per `/10x-e2e`), consistent with CLAUDE.md's note that E2E tooling isn't yet instantiated.

## Code References

- `backend/.../analysis/AnalysisRepository.java:9` — `findByIdAndOwnerId` (reuse for reopen); **no list-by-owner method (add here)**.
- `backend/.../analysis/AnalysisService.java:64-72` — owner-scoped read + 404-no-leak pattern to copy for the list.
- `backend/.../analysis/AnalysisController.java:27-37` — controller pattern + `@AuthenticationPrincipal` snippet; **add `GET /api/analyses`**.
- `backend/.../analysis/dto/AnalysisResponse.java:8-15` — heavy detail DTO; **add `AnalysisSummaryResponse`**.
- `backend/.../auth/SecurityConfig.java:31-52` — `.anyRequest().authenticated()` auto-gates the new GET; GET already in CORS.
- `backend/.../auth/SecurityUtils.java:7-24` — the owner-scoping query convention (the guardrail rule).
- `backend/src/main/resources/db/changelog/changes/002-create-analyses.yaml` — `created_at` exists; **no owner_id/created_at index (add changeset)**.
- `frontend/src/app/analyses/[id]/page.tsx` — the reopen target (reuse as-is); **add sibling `app/analyses/page.tsx`**.
- `frontend/src/lib/analyses.ts` — `getAnalysis(id)` exists; **add `getAnalyses()` + `AnalysisSummary` type**.
- `frontend/src/proxy.ts` — `/analyses/:path*` matcher already protects the new list page.
- `frontend/src/app/dashboard/page.tsx` — pre-designed home for the "Moje analizy / Historia" link.
- `backend/src/test/.../analysis/AnalysisIsolationTest.java` — no-existence-leak template + the "list-isolation deferred to S-03" marker.
- `backend/src/test/.../analysis/AnalysisRepositoryTest.java:56-69` — repository owner-scope test to mirror for the list query.

## Architecture Insights

- **Owner-scoping is a query-layer invariant, codified.** Ownership is enforced by *scoping the query on `ownerId`*, never by fetch-then-filter. A foreign or missing id both return an identical 404 (no existence leak). S-03's list must call `findByOwnerId...(currentUserId())`, never `findAll()` + filter — this is the single most important correctness constraint of the slice.
- **S-01 pre-shaped the routes for S-03.** `/analyses/new` (transient action) and `/analyses/[id]` (durable owner-scoped result) were split deliberately so the reopen target and the future list wouldn't force a rework. The reopen half is effectively done; S-03 is mostly the list.
- **Read/write asymmetry in DTOs.** The detail DTO exists; the summary DTO does not. A JPQL constructor-expression projection straight into `AnalysisSummaryResponse` would avoid loading lazy collections entirely (no transaction needed) — cleaner than mapping full entities for a list.
- **Session-cookie + SPA-CSRF, GET is the easy case.** Because the new surface is read-only GET, it inherits auth, CORS, and CSRF handling for free — the slice touches no security config.
- **Perf is a non-issue at MVP scale but the index is cheap insurance.** `target_scale` is small/low-QPS, so the missing `owner_id` index won't bite functionally; still, adding `(owner_id, created_at)` is a trivial, correctness-neutral changeset worth including.

## Historical Context (from prior changes)

- **List endpoint deliberately omitted at S-01, deferred here.** `context/archive/2026-07-06-analyze-and-save-contract/plan.md:32` — *"No history list — `/analyses/{id}` is reachable by direct link; a list view is S-03. (`AnalysisRepository` gets `findByIdAndOwnerId` only, not `findAllByOwnerId`.)"*
- **Dashboard was designed as the list's home.** `context/archive/2026-07-06-analyze-and-save-contract/research.md:140-142` — the dashboard "becomes the S-03 history list later," and the `/analyses/new` vs `/analyses/[id]` split was chosen so making the dashboard the list "won't force a rework at S-03."
- **Owner-scoping convention established at F-01.** `context/archive/2026-07-04-identity-and-isolation/` — the `User` entity, the query-layer isolation invariant, and the `SecurityUtils.currentUserId()` helper.
- **Isolation-test cookbook exists.** `context/foundation/test-plan.md` §6.4 ("Adding a cross-user isolation test") and §6.5 (mocked-LLM classification) — S-03 should extend §6.4 with the still-missing **list-never-leaks** case.
- **Auth-boundary matrix is extensible.** `context/archive/2026-07-06-testing-auth-boundary-regression/` — a parameterized route matrix proves anonymous→401; adding `GET /api/analyses` to it extends coverage for free.

## Related Research

- `context/archive/2026-07-06-analyze-and-save-contract/research.md` — the S-01 persistence + routing design this slice builds on.
- `context/archive/2026-07-04-identity-and-isolation/research.md` — the owner-scoping / isolation foundation.

## Open Questions

1. **List payload shape** — return a trimmed `AnalysisSummaryResponse` (id/title/status/createdAt) or also include a clause count / top risk level for a richer row? (Recommendation: minimal summary now; counts only if the UI needs them — avoids an N+1 or a heavier projection.)
2. **Pagination** — plain `List` (fine at MVP small-scale) or `Page`/`Sort` from the start? (Recommendation: plain owner-scoped list ordered `createdAt DESC`; pagination is above-MVP for the stated scale.)
3. **Empty state** — the list's zero-analyses view (first-time user). Not covered by any existing screen; needs a Polish empty state consistent with the disclaimer tone.
4. **List home** — a dedicated `/analyses` page vs. folding the list into `/dashboard`. The S-01 research leaned toward the dashboard becoming the list; the roadmap says "see a list … and reopen." Either is viable — a `/analyses` page keeps the dashboard as a launcher; decide in planning.
5. **Frontend E2E** — does S-03 introduce the repo's first Playwright test (per `/10x-e2e`), or is the backend `list-never-leaks` integration test sufficient for the guardrail? (The guardrail is backend-enforced, so the integration test is the load-bearing one; a browser test is optional polish.)

# Analysis History (S-03) Implementation Plan

## Overview

Build the **list** half of roadmap slice S-03: an owner-scoped `GET /api/analyses` endpoint that returns a lightweight summary of each of the current user's saved analyses, a history list folded into the existing `/dashboard` page (each row reopens the already-shipped `/analyses/[id]` view), and — new for this repository — a Playwright E2E harness that verifies the list-and-reopen flow in a browser.

The "reopen one analysis" half already ships end-to-end (`GET /api/analyses/{id}` + `app/analyses/[id]/page.tsx`), so this plan adds only what is symmetrically missing for a *list*, plus the guardrail test that S-01 explicitly deferred to S-03.

## Current State Analysis

From `context/changes/analysis-history/research.md` (commit `48a46d0`):

- **Repository** (`backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisRepository.java:9`) has only `findByIdAndOwnerId(id, ownerId)`. No list-by-owner query. S-01's plan reserved `findAllByOwnerId` for this slice.
- **Service** (`.../analysis/AnalysisService.java:64-72`) enforces ownership at the query level and returns **404 (not 403)** on a foreign/missing id — the no-existence-leak pattern to mirror.
- **Controller** (`.../analysis/AnalysisController.java:27-37`) exposes only `POST /api/analyses` and `GET /api/analyses/{id}`, both resolving the user via `@AuthenticationPrincipal AppUserDetails principal` → `principal.getId()`. No list endpoint.
- **DTO** (`.../analysis/dto/AnalysisResponse.java:8-15`) is heavy (embeds all clauses + points). No lightweight summary record.
- **Entity** (`.../analysis/Analysis.java`) already carries `createdAt` (`Instant`, `created_at timestamptz not null`) — the ordering key exists and is populated.
- **Security** (`.../auth/SecurityConfig.java:31-52`) — `.anyRequest().authenticated()` auto-gates any new GET; CORS already allows GET; `csrf.spa()` exempts GET. **No security config change needed.**
- **Migration** — `002-create-analyses.yaml` has `created_at` but **no index** on `owner_id`. Next changeset is `003-*`.
- **Frontend** — `/dashboard` (`frontend/src/app/dashboard/page.tsx`) is a client component that calls `me()` and shows a "Nowa analiza" link (`/analyses/new`) + Logout; it was pre-designed as the history list's home. The API client `frontend/src/lib/api.ts` (`apiFetch`, cookie + CSRF aware) and domain client `frontend/src/lib/analyses.ts` (`getAnalysis(id)`) exist; there is no `getAnalyses()` list call and no summary type. Route protection via `frontend/src/proxy.ts` already covers `/dashboard`. Installed shadcn/ui includes `card`, `badge`, `skeleton`, `button` — enough to build the list from `Card` rows wrapped in `Link`.
- **Tests** — the 3-layer owner-isolation template exists (`AnalysisRepositoryTest`, `AnalysisFlowTest.crossUserCannotReadAnotherUsersAnalysis`, `AnalysisIsolationTest`). `AnalysisIsolationTest` comments *"List-isolation … is deferred to S-03 — there is no list endpoint yet."* The anonymous→401 route matrix lives at `backend/src/test/java/com/morawski/dev/falcon/auth/AuthBoundaryMatrixTest.java`. **No frontend test tooling exists** — `frontend/package.json` has only `dev/build/start/lint`; there is no `playwright.config.*`.

## Desired End State

A returning, logged-in user opens `/dashboard` and sees their past analyses listed newest-first (title, status, date), each reopening the full breakdown at `/analyses/[id]`. A user with no analyses sees a Polish empty-state with a call-to-action to start a new analysis. The list is strictly owner-scoped — no user can ever see another user's rows — proven by a backend integration test and exercised end-to-end by a Playwright browser test.

**Verify**: `./mvnw test` passes (incl. the new list-never-leaks test); `pnpm --dir frontend lint` passes; `pnpm --dir frontend exec playwright test` passes; manually, two accounts each see only their own analyses on the dashboard.

### Key Discoveries:

- Reopen target already exists — history rows only need `<Link href={`/analyses/${id}`}>` (`frontend/src/app/analyses/[id]/page.tsx` keys off the URL id).
- Owner-scoping is a mandated query-layer convention (`.../auth/SecurityUtils.java:7-24`): use `findByOwnerId...(ownerId)`, **never** `findAll()` + filter.
- A JPQL constructor-expression projection into `AnalysisSummaryResponse` avoids loading the lazy `clauses`/`negotiationPoints` collections entirely — no `@Transactional` needed for the list read.
- The deferred **list-never-leaks** test is this slice's explicit obligation (`AnalysisIsolationTest` code comment).
- E2E must follow CLAUDE.md's hard rules and the `/10x-e2e` skill: role/label locators, no `waitForTimeout`, per-test unique ids + cleanup.

## What We're NOT Doing

- **No new `/analyses` list route** — the list folds into `/dashboard` (user decision). `app/analyses/` keeps only `new/` and `[id]/`.
- **No pagination** — plain owner-scoped list ordered `createdAt DESC` (MVP small-scale). No `Page`/`Sort` params, no paging UI.
- **No clause counts / risk badges on list rows** — minimal summary only (id, title, status, createdAt). Risk detail stays on the reopened analysis view.
- **No delete action** — that is S-04 (`delete-analysis`), a separate slice.
- **No changes to `GET /api/analyses/{id}`, the analysis result view, security config, or CORS** — all reused as-is.
- **No shared header/nav component** — the entry point is the dashboard itself; a global nav is out of scope.

## Implementation Approach

Three phases, back to front: (1) a backend vertical (migration → repository projection → summary DTO → service → controller) shipped together with its guardrail tests, because the owner-scoped query and the list-never-leaks test are the load-bearing correctness surface and belong together; (2) the dashboard list UI consuming the new endpoint; (3) the Playwright harness and browser test. Each phase is independently verifiable and pauses for manual confirmation before the next.

## Critical Implementation Details

- **JPQL projection must reference the DTO by fully-qualified constructor** — `select new com.morawski.dev.falcon.analysis.dto.AnalysisSummaryResponse(a.id, a.title, a.status, a.createdAt) from Analysis a where a.ownerId = :ownerId order by a.createdAt desc`. This keeps the read off the lazy collections; a derived-name method (`findByOwnerIdOrderByCreatedAtDesc`) returning entities would work but then mapping must stay inside a read-only transaction. Prefer the projection.
- **Ordering is a tested contract here, unlike clause order.** The list's `createdAt DESC` order must be explicit in the query, and the isolation/ordering test must assert it — `Analysis` collections carry no `@OrderBy`, so nothing guarantees order implicitly.

## Phase 1: Backend list endpoint (owner-scoped) + guardrail tests

### Overview

Add the owner-scoped list query, a lightweight summary DTO, the service method, and the `GET /api/analyses` endpoint — with the deferred list-isolation test and repository/auth-matrix coverage.

### Changes Required:

#### 1. Migration — index for the history query

**File**: `backend/src/main/resources/db/changelog/changes/003-index-analyses-owner.yaml` (new), wired into `backend/src/main/resources/db/changelog/db.changelog-master.yaml` via `include`.

**Intent**: Add a composite index supporting filter-by-owner + order-by-createdAt so the history query doesn't full-scan; also covers the currently-unindexed `owner_id` FK.

**Contract**: New Liquibase changeset creating an index on `analyses (owner_id, created_at)` (createdAt descending is fine as a plain index for this query shape). Follow the changeset id/author style of `002-create-analyses.yaml`.

#### 2. Summary DTO

**File**: `backend/src/main/java/com/morawski/dev/falcon/analysis/dto/AnalysisSummaryResponse.java` (new)

**Intent**: A lightweight row shape for the list — no clauses/points.

**Contract**: `record AnalysisSummaryResponse(Long id, String title, AnalysisStatus status, Instant createdAt)`. Must be a top-level record so the JPQL constructor expression can target it.

#### 3. Repository — owner-scoped list projection

**File**: `backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisRepository.java`

**Intent**: The list-by-owner query that S-01 reserved for this slice, projecting straight into the summary DTO.

**Contract**: A `@Query` method `List<AnalysisSummaryResponse> findSummariesByOwnerId(Long ownerId)` using the fully-qualified constructor expression ordered `createdAt desc` (see Critical Implementation Details). Owner filter is in the query — never a bare `findAll`.

#### 4. Service — list method

**File**: `backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisService.java`

**Intent**: Expose the owner-scoped list to the controller, mirroring how `getAnalysis` takes an explicit `ownerId`.

**Contract**: `List<AnalysisSummaryResponse> listAnalyses(Long ownerId)` delegating to `findSummariesByOwnerId(ownerId)`. No transaction needed (projection doesn't touch lazy collections).

#### 5. Controller — GET /api/analyses

**File**: `backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisController.java`

**Intent**: The list endpoint, copying the existing current-user resolution.

**Contract**: `@GetMapping` (no path) `List<AnalysisSummaryResponse> list(@AuthenticationPrincipal AppUserDetails principal)` → `analysisService.listAnalyses(principal.getId())`, 200. No security/CORS change (auto-gated GET).

#### 6. Tests — repository, list-isolation, auth matrix

**Files**: `backend/src/test/java/com/morawski/dev/falcon/analysis/AnalysisRepositoryTest.java`, `.../analysis/AnalysisIsolationTest.java`, `.../auth/AuthBoundaryMatrixTest.java`

**Intent**: Prove the list is owner-scoped at the repository and HTTP layers (the deferred S-03 guardrail), and that the endpoint is anonymous-gated.

**Contract**:
- Repository test: seed two owners each with analyses; assert `findSummariesByOwnerId(ownerA)` returns only A's rows, in `createdAt DESC` order (assert order explicitly).
- `AnalysisIsolationTest` (**list-never-leaks**): seed A's and B's analyses (clauses only, no linked points → simple one-step teardown), authenticate as B, `GET /api/analyses`, assert response contains B's rows and none of A's. Remove/replace the "deferred to S-03" comment.
- Add a `GET /api/analyses` row to the parameterized route matrix so anonymous → 401 is covered.
- Reuse the `persistUser` helper + `.with(user(new AppUserDetails(owner)))` idiom; GET drops `.with(csrf())`.

### Success Criteria:

#### Automated Verification:

- Migration 003 applies via the test context: `cd backend && ./mvnw test` boots Spring against Testcontainers Postgres and runs Liquibase (a bad changeset fails the build loudly)
- Backend tests pass: `cd backend && ./mvnw test`
- The new list-never-leaks test fails if the query is changed to a bare `findAll()` (sanity: guardrail actually guards)
- `GET /api/analyses` present in the auth-boundary matrix and returns 401 anonymously

#### Manual Verification:

- `GET /api/analyses` (authenticated, via browser/curl with session cookie) returns the user's analyses newest-first with only id/title/status/createdAt
- A second account's `GET /api/analyses` never shows the first account's analyses

**Implementation Note**: After this phase and all automated verification passes, pause for manual confirmation before Phase 2.

---

## Phase 2: Dashboard history list (frontend)

### Overview

Consume the new endpoint and render the owner's analyses on `/dashboard` as reopenable rows, with a Polish empty-state CTA.

### Changes Required:

#### 1. API client — list call + summary type

**File**: `frontend/src/lib/analyses.ts`

**Intent**: A typed list fetch mirroring `getAnalysis`.

**Contract**: `type AnalysisSummary = { id: number; title: string; status: AnalysisStatus; createdAt: string }` and `async function getAnalyses(): Promise<AnalysisSummary[]>` → `apiFetch("/api/analyses")` (GET, no CSRF). Reuse the existing `AnalysisStatus` type.

#### 2. Dashboard — render the list + empty state

**File**: `frontend/src/app/dashboard/page.tsx`

**Intent**: Fold the history list into the existing dashboard: after the `me()` guard, fetch and show the analyses; keep the "Nowa analiza" and Logout actions.

**Contract**: Client component calls `getAnalyses()` (handling 401 → `/login` like the existing pattern). Renders newest-first `Card` rows, each wrapped in `<Link href={`/analyses/${id}`}>` showing title, `status`, and a formatted `createdAt` (Polish locale). While loading, show the `skeleton` primitive. When the list is empty, show a Polish message (e.g. "Nie masz jeszcze żadnych analiz") + a primary button to `/analyses/new`. Status labels/formatting consistent with the app's existing idiom (`frontend/src/lib/risk.ts` for any risk labels if used; otherwise plain status text).

### Success Criteria:

#### Automated Verification:

- Lint passes: `pnpm --dir frontend lint`
- Production build passes: `pnpm --dir frontend build`

#### Manual Verification:

- Dashboard lists the logged-in user's analyses newest-first; clicking a row opens `/analyses/[id]` with the correct breakdown
- A brand-new account sees the empty-state CTA and the button routes to `/analyses/new`
- Loading state shows skeletons, not a flash of empty/error
- Two accounts each see only their own analyses

**Implementation Note**: After this phase and all automated verification passes, pause for manual confirmation before Phase 3.

---

## Phase 3: First Playwright E2E harness + history test

### Overview

Stand up the repository's first Playwright setup in `frontend/` and add a browser test for the list-and-reopen flow, following CLAUDE.md's E2E rules and the `/10x-e2e` skill.

### Changes Required:

#### 1. Playwright harness

**Files**: `frontend/package.json` (add `@playwright/test` dev dependency + a `test:e2e` script), `frontend/playwright.config.ts` (new), `frontend/e2e/` (new test dir), plus `.gitignore` entries for Playwright artifacts (`playwright-report/`, `test-results/`).

**Intent**: Minimal Playwright config targeting the app at `http://localhost:3000`, assuming a running backend + frontend (per the app's local-run flow).

**Contract**: `playwright.config.ts` with `testDir: "e2e"`, `baseURL` from env (default `http://localhost:3000`), DOM/snapshot mode (no `--caps=vision`). Optionally a `webServer` block to boot `pnpm dev` — decide during implementation based on whether the backend must also be up (the test needs a real logged-in session + seeded analyses).

#### 2. History E2E test

**File**: `frontend/e2e/analysis-history.spec.ts` (new)

**Intent**: Verify that a user's saved analyses appear on the dashboard and reopen correctly, owner-scoped from the user's perspective.

**Contract**: A standalone test that (a) registers/logs in a unique user (timestamp-suffixed email) via the real auth flow, (b) creates an analysis through the app (paste → submit) or a seeded fixture, (c) navigates to `/dashboard` and asserts the analysis appears (locate by role/text — its title), (d) clicks it and asserts the `/analyses/[id]` breakdown is visible (`waitForURL` / `toBeVisible`, never `waitForTimeout`), (e) cleans up its own data. Follow the five anti-patterns from `/10x-e2e`. Use `getByRole`/`getByText`/`getByLabel`; `getByTestId` only if accessibility attributes are ambiguous.

### Success Criteria:

#### Automated Verification:

- Playwright installed and config valid: `pnpm --dir frontend exec playwright --version`
- E2E test passes against a running app: `pnpm --dir frontend exec playwright test`
- Test uses no `waitForTimeout` and no CSS/XPath selectors (grep the spec)

#### Manual Verification:

- Re-running the test twice in a row passes (independence + cleanup verified)
- The test fails if the dashboard list is broken (e.g. temporarily point `getAnalyses()` at a wrong path) — confirms it actually exercises the feature

**Implementation Note**: After this phase and all automated verification passes, the slice is complete — proceed to `/10x-impl-review` then `/10x-archive`.

---

## Testing Strategy

### Unit / Integration Tests (backend):
- Repository owner-scope + ordering test for `findSummariesByOwnerId`.
- HTTP list-never-leaks isolation test (the deferred S-03 guardrail).
- Anonymous→401 coverage via the auth-boundary route matrix.

### E2E Tests (frontend):
- Dashboard list shows the user's analyses and reopens one (Playwright, first in repo).

### Manual Testing Steps:
1. Log in as user A, create two analyses; confirm both appear on `/dashboard` newest-first.
2. Click a row; confirm the correct breakdown opens at `/analyses/[id]`.
3. Log in as user B (fresh account); confirm the empty-state CTA shows and routes to `/analyses/new`; confirm none of A's analyses are visible.
4. Create an analysis as B; confirm A's dashboard still shows only A's analyses.

## Performance Considerations

At the PRD's small/low-QPS scale, a plain owner-scoped list is fine. The `(owner_id, created_at)` index keeps the query from full-scanning as history grows. The summary projection avoids hydrating clause/point rows per analysis — the main efficiency lever.

## Migration Notes

Additive only: one new index changeset (`003-*`), no data backfill, no column changes. Rollback = drop the index; no application data is altered.

## References

- Related research: `context/changes/analysis-history/research.md`
- Reopen target (reuse): `frontend/src/app/analyses/[id]/page.tsx`
- Owner-scoping convention: `backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisService.java:64-72`, `.../auth/SecurityUtils.java:7-24`
- Isolation test template: `backend/src/test/java/com/morawski/dev/falcon/analysis/AnalysisIsolationTest.java`
- Auth route matrix: `backend/src/test/java/com/morawski/dev/falcon/auth/AuthBoundaryMatrixTest.java`
- E2E rules: `CLAUDE.md` (§ 10xDevs Module 3 Lesson 4) + `/10x-e2e` skill

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Backend list endpoint (owner-scoped) + guardrail tests

#### Automated

- [x] 1.1 Migration 003 applies via test context (`./mvnw test` runs Liquibase on Testcontainers Postgres) — 16de592
- [x] 1.2 Backend tests pass: `cd backend && ./mvnw test` — 16de592
- [x] 1.3 List-never-leaks test fails if query changed to bare `findAll()` (guardrail sanity) — 16de592
- [x] 1.4 `GET /api/analyses` in auth-boundary matrix returns 401 anonymously — 16de592

#### Manual

- [x] 1.5 Authenticated `GET /api/analyses` returns owner's analyses newest-first, summary fields only — 16de592
- [x] 1.6 Second account's `GET /api/analyses` never shows the first account's analyses — 16de592

### Phase 2: Dashboard history list (frontend)

#### Automated

- [x] 2.1 Lint passes: `pnpm --dir frontend lint` — b3098a9
- [x] 2.2 Production build passes: `pnpm --dir frontend build` — b3098a9

#### Manual

- [x] 2.3 Dashboard lists analyses newest-first; row click opens correct `/analyses/[id]` — b3098a9
- [x] 2.4 New account sees Polish empty-state CTA routing to `/analyses/new` — b3098a9
- [x] 2.5 Loading state shows skeletons, no empty/error flash — b3098a9
- [x] 2.6 Two accounts each see only their own analyses — b3098a9

### Phase 3: First Playwright E2E harness + history test

#### Automated

- [x] 3.1 Playwright installed and config valid: `pnpm --dir frontend exec playwright --version`
- [x] 3.2 E2E test passes: `pnpm --dir frontend exec playwright test`
- [x] 3.3 Spec uses no `waitForTimeout` and no CSS/XPath selectors

#### Manual

- [x] 3.4 Re-running the test twice passes (independence + cleanup)
- [x] 3.5 Test fails when the dashboard list is deliberately broken (exercises the feature)

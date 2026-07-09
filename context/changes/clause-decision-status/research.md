---
date: 2026-07-09T09:07:37+02:00
researcher: Mateusz Morawski
git_commit: 6cf9d3fea8f60a0386b4198ca8fa30c751969840
branch: main
repository: falcon-contracts
topic: "S-02 / FR-007 — per-clause decision status (accepted / to-negotiate / rejected)"
tags: [research, codebase, clause-decision-status, owner-scoping, spring-security, cors, csrf, jpa, playwright]
status: complete
last_updated: 2026-07-09
last_updated_by: Mateusz Morawski
---

# Research: S-02 / FR-007 — per-clause decision status

**Date**: 2026-07-09T09:07:37+02:00
**Researcher**: Mateusz Morawski
**Git Commit**: `6cf9d3fea8f60a0386b4198ca8fa30c751969840`
**Branch**: `main`
**Repository**: `morawski-dev/falcon-contracts`

## Research Question

What does the codebase already provide, and what must change, to let a logged-in user set each clause's decision status (`ACCEPTED` / `TO_NEGOTIATE` / `REJECTED`, default `PENDING`) on their own saved analysis — strictly owner-scoped, per PRD FR-007 and roadmap slice S-02?

## Summary

**The read half of FR-007 is already shipped; S-02 is purely the write half.** S-01 (`analyze-and-save-contract`) deliberately created the `ClauseDecision` enum, the non-null `user_decision` column defaulting to `PENDING`, and exposed `userDecision` *and the clause's `id`* on `ClauseResponse` — then explicitly deferred mutation: *"No per-clause decision status UI/endpoint — `Clause.userDecision` defaults to `PENDING`; mutating it is S-02."* (`context/archive/2026-07-06-analyze-and-save-contract/plan.md:31`).

So the change is small and its shape is nearly forced by existing convention. Four things are missing, and one of them is a genuine blocker that is easy to overlook:

1. **`Clause` has no setter.** Zero setters exist on the entity — the whole domain is read-only-after-construction.
2. **CORS rejects `PATCH`.** `SecurityConfig` allows only `GET, POST`. The browser calls Spring cross-origin (`localhost:3000` → `localhost:8080`), so a `PATCH` **fails at preflight** before any code runs. This is the one required security-config edit.
3. **No write endpoint / service method / request DTO.**
4. **No UI control, no Polish decision labels, and no installed shadcn primitive** for a 3-way choice.

The owner-scoping invariant needs no invention — reuse `analysisRepository.findByIdAndOwnerId(...)`, locate the clause *inside* the loaded aggregate, and let JPA dirty-checking flush. That inherits the existing "404, never 403, byte-identical to a missing id" anti-enumeration property for free.

Two decisions are genuinely open (nothing pre-commits them): **PATCH vs PUT**, and **whether `Analysis.status` should flip `ANALYZED` → `REVIEWED`**.

## Detailed Findings

### Backend: the write path and the ownership invariant

`AnalysisController` is a thin `@RestController @RequestMapping("/api/analyses")` with three endpoints, each resolving the caller via `@AuthenticationPrincipal AppUserDetails principal` and passing `principal.getId()` down as `ownerId`. The controller **never checks ownership itself** — it delegates to the service, which scopes the *query*.

The single read path is the template to copy ([`AnalysisService.java:69-77`](https://github.com/morawski-dev/falcon-contracts/blob/6cf9d3fea8f60a0386b4198ca8fa30c751969840/backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisService.java#L69-L77)):

```java
@Transactional(readOnly = true)
public AnalysisResponse getAnalysis(Long id, Long ownerId) {
    Analysis analysis = analysisRepository.findByIdAndOwnerId(id, ownerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return toResponse(analysis);
}
```

`AnalysisRepository` exposes exactly two methods, both owner-scoped by construction: `findByIdAndOwnerId(Long, Long)` and a `findSummariesByOwnerId(...)` JPQL projection. There is **no bare `findById`** for owned entities and **no `ClauseRepository`** — clauses are reachable only through their `Analysis` aggregate. `SecurityUtils`' Javadoc states the rule outright: *"Every repository method returning owner-scoped data takes an `ownerId` parameter … there is no bare `findById` for owned entities."*

Because `findByIdAndOwnerId` returns `Optional.empty()` both when the id doesn't exist *and* when it belongs to someone else, "not found" and "not yours" collapse into one identical `404`. There is no `403` for ownership anywhere. A `403` only ever means a CSRF failure.

**Persistence mechanics.** `Analysis.clauses` is `@OneToMany(mappedBy = "analysis", cascade = ALL, orphanRemoval = true)` with **no `@OrderBy`**; `Clause` is the `@ManyToOne` owning side with `@Enumerated(STRING) @Column(name = "user_decision", nullable = false, length = 20)`. A `Clause` reached via `analysis.getClauses()` inside an open transaction is a managed entity, so mutating it through a (new) setter flushes on commit with no explicit `save()`. `spring.jpa.open-in-view=false` in both the main and test profiles, so the mutation must happen inside the transactional service method, not in the controller or DTO mapper.

**The gaps, concretely:**

- [`Clause.java:58-80`](https://github.com/morawski-dev/falcon-contracts/blob/6cf9d3fea8f60a0386b4198ca8fa30c751969840/backend/src/main/java/com/morawski/dev/falcon/analysis/Clause.java#L58-L80) — six getters, **zero setters**. `userDecision` is initialised to `PENDING` in the constructor (`Clause.java:54`).
- [`SecurityConfig.java:75`](https://github.com/morawski-dev/falcon-contracts/blob/6cf9d3fea8f60a0386b4198ca8fa30c751969840/backend/src/main/java/com/morawski/dev/falcon/auth/SecurityConfig.java#L75) — `configuration.setAllowedMethods(List.of("GET", "POST"))`. **A `PATCH` dies at CORS preflight.** Must become `List.of("GET", "POST", "PATCH")` (or `PUT`).
- No `UpdateClauseDecisionRequest` record, no service method, no controller handler.

**Non-gaps (already in place, do not rebuild):**

- `ClauseResponse` **already exposes `Long id`** as its first component — the frontend can address an individual clause today, with no DTO change.
- `.anyRequest().authenticated()` (`SecurityConfig.java:42`) auto-gates any new `/api/analyses/**` route; no authorization rule to add.
- `.csrf(csrf -> csrf.spa())` (`SecurityConfig.java:36`) already protects `PATCH`, and `X-XSRF-TOKEN` is already in `setAllowedHeaders` (`SecurityConfig.java:76`).
- Jackson rejects an invalid enum value with an automatic `400`, so a `ClauseDecision`-typed request field validates for free.

### Frontend: the established mutation shape

Everything is a **client component**; there are no Server Components fetching data and **no Server Actions anywhere**. The reason is structural: `apiFetch` reads `document.cookie` to lift the CSRF token, so it cannot run on the server.

- [`frontend/src/proxy.ts`](https://github.com/morawski-dev/falcon-contracts/blob/6cf9d3fea8f60a0386b4198ca8fa30c751969840/frontend/src/proxy.ts) is Next 16's renamed middleware. It is a **pure route guard** (checks for a `JSESSIONID` cookie on `/dashboard/*` and `/analyses/*`, redirects to `/login` if absent). It does **not** proxy API traffic — the browser calls Spring directly at `NEXT_PUBLIC_API_BASE_URL`, cross-origin. *This is exactly why the CORS method list is load-bearing.*
- [`frontend/src/lib/api.ts`](https://github.com/morawski-dev/falcon-contracts/blob/6cf9d3fea8f60a0386b4198ca8fa30c751969840/frontend/src/lib/api.ts) — `apiFetch` sends `credentials: "include"`, defaults `Content-Type: application/json`, and for any non-`GET`/`HEAD` method reads the `XSRF-TOKEN` cookie into an `X-XSRF-TOKEN` header. **`PATCH` is already supported with no change.** Non-`ok` throws `ApiError(status, message)`, message lifted from a JSON `{ "error": ... }` body.
- The **mutation idiom** to copy is `frontend/src/app/analyses/new/page.tsx:28-52` (and `(auth)/login/page.tsx:27-39`): `"use client"`, `useState` for input + `error: string | null` + `submitting: boolean`; an `async handleSubmit` doing `await getCsrf()` then the typed wrapper, wrapped in `try/catch (err instanceof ApiError)` / `finally { setSubmitting(false) }`. No `useTransition`, no `revalidatePath`, no `router.refresh()`.
- [`frontend/src/app/analyses/[id]/page.tsx`](https://github.com/morawski-dev/falcon-contracts/blob/6cf9d3fea8f60a0386b4198ca8fa30c751969840/frontend/src/app/analyses/%5Bid%5D/page.tsx) is a client component fetching in `useEffect`, rendering `analysis.clauses.map(clause => <Card key={clause.id}>)` at line 88-89. Inside `CardContent` (line 90) sits a badge row (risk level + risk type), the clause text, the rationale, then linked negotiation points. **`userDecision` is never rendered today** — it arrives on the wire and is dropped.
- [`frontend/src/lib/risk.ts`](https://github.com/morawski-dev/falcon-contracts/blob/6cf9d3fea8f60a0386b4198ca8fa30c751969840/frontend/src/lib/risk.ts) holds the Polish label convention: `UPPER_SNAKE_EXPORT: Record<Enum, string>`, e.g. `RISK_LEVEL_LABEL = { HIGH: "Wysokie", ... }`. **No `CLAUSE_DECISION_LABEL` exists.**

**One genuinely new interaction shape.** Every existing mutation navigates away on success (`router.push`). A per-clause status control must instead update in place — optimistic local state or a re-fetch. There is no precedent in the codebase for this.

**UI primitives.** Installed under `components/ui/`: `alert`, `badge`, `button`, `card`, `input`, `label`, `separator`, `skeleton`, `textarea`. **Absent:** `select`, `dropdown-menu`, `radio-group`, `toggle-group`, and any toast (`sonner`). However `package.json` carries the *unified* `radix-ui` package (`^1.6.1`) — `button.tsx` and `badge.tsx` already import `{ Slot } from "radix-ui"` — so the underlying Radix primitives are present and only the shadcn wrapper file is missing. `Button` already has `xs`/`sm`/`icon-*` sizes and `outline`/`secondary`/`ghost`/`destructive` variants, so a segmented row of buttons is achievable with zero new dependencies.

### Testing: what a new mutation endpoint obliges

The test suite encodes the isolation guardrail as a three-layer template, and `context/foundation/test-plan.md` §2 Risk #1 explicitly names *mutating* another user's analysis as the protect-first risk.

- **Auth idiom is never `@WithMockUser`.** Every backend MVC test uses `.with(user(new AppUserDetails(someUser)))`. Integration tests are `@Import(TestcontainersConfiguration.class) @SpringBootTest @AutoConfigureMockMvc` — **never `@DataJpaTest`** (no embedded DB). Not auto-rolled-back, so each class has an `@AfterEach ... deleteAll()`.
- **Mutations require `.with(csrf())`.** Existing `AnalysisIsolationTest` cases are GET-only and omit it; a `PATCH` test that forgets it gets a confusing `403`.
- `AnalysisIsolationTest.java:73-82` asserts the cross-user `404` is **byte-identical** (status *and* body) to a truly-missing id. Note the caveat recorded in `context/archive/2026-07-06-testing-classification-pipeline-isolation/reviews/impl-review.md:44-50` (finding F2): under MockMvc both bodies are literally `""`, so the body-equality assertion is defense-in-depth rather than a meaningfully exercised contract.
- `AuthBoundaryMatrixTest.java` is only *partially* a matrix: its `@MethodSource("protectedGetRoutes")` stream fires **GETs only**. Mutations are hand-written `@Test`s (`anonymousPostWithoutCsrfReturns403` at `:47-50`, `anonymousPostWithCsrfReturns401` at `:53-56`). Adding a `PATCH` route therefore means **two new `@Test` methods**, not a new row.
- `AnalysisFlowTest.java` mocks the LLM with a nested `@TestConfiguration` exposing a `@Bean @Primary ChatModel` as a **plain lambda** returning a fixed `ChatResponse` — deliberately not Mockito, to dodge a `getOptions()` NPE (see the class Javadoc at `:42-43`). **A decision test needs no LLM mock at all** — seed the analysis directly through the repository.
- **Playwright** (`frontend/playwright.config.ts`, `frontend/e2e/analysis-history.spec.ts`): `testDir: "./e2e"`, `fullyParallel`, chromium only, **no `webServer`, no fixtures, no `storageState`**. The spec registers a fresh account through the UI each run with a `Date.now()` suffix for uniqueness, uses only `getByLabel`/`getByRole`/`getByText`, waits via `waitForURL` and `toBeVisible()`, and has **no cleanup** (blocked until S-04 ships delete). It seeds through the real UI against the **live LLM**, which is why CI does not run it.
- **CI** (`.github/workflows/ci.yml`) uses `dorny/paths-filter` to gate two jobs. Backend runs `./mvnw -B -ntp clean package` (full test suite, Testcontainers Postgres, LLM mocked → no API key needed). Frontend runs only `pnpm lint` + `pnpm build`. **Playwright is a local-only gate.**

Because test properties set `spring.jpa.hibernate.ddl-auto=validate`, any schema change must land as a Liquibase changelog first or the whole Spring context fails to boot. **S-02 needs no schema change** — the column already exists (`002-create-analyses.yaml:77`).

## Code References

- `backend/.../analysis/AnalysisService.java:69-77` — the owner-scoped read to mirror; `:36` `createAnalysis` uses an explicit `TransactionTemplate` (self-invocation would defeat `@Transactional`)
- `backend/.../analysis/AnalysisRepository.java:13` — `findByIdAndOwnerId`, the ownership primitive
- `backend/.../analysis/AnalysisController.java:20-45` — three endpoints; `@AuthenticationPrincipal` → `principal.getId()`
- `backend/.../analysis/Clause.java:41-43` — the `user_decision` column; `:54` PENDING default; `:58-80` getters only, **no setter**
- `backend/.../analysis/Analysis.java:42` — `@OneToMany(cascade = ALL, orphanRemoval = true)`, **no `@OrderBy`**
- `backend/.../analysis/dto/ClauseResponse.java:8` — `Long id` already on the wire
- `backend/.../analysis/AnalysisExceptionHandler.java:13-17` — `@RestControllerAdvice`, `{"error": "..."}`, only maps `AnalysisFailedException` → 502
- `backend/.../auth/SecurityConfig.java:36` `csrf.spa()`; `:42` `anyRequest().authenticated()`; **`:75` `setAllowedMethods(List.of("GET","POST"))` ← the blocker**; `:76` `X-XSRF-TOKEN` allowed
- `backend/src/main/resources/db/changelog/changes/002-create-analyses.yaml:77` — `user_decision` column already migrated
- `frontend/src/lib/api.ts:19,26` — CSRF header auto-added for non-GET; `PATCH` already works
- `frontend/src/lib/analyses.ts:5` `ClauseDecision` union; `:16` `Clause.id`; `:21` `userDecision`; `:47-54` `createAnalysis` (the wrapper shape to copy)
- `frontend/src/app/analyses/[id]/page.tsx:88-98` — clause `Card`; the insertion point for a status control
- `frontend/src/app/analyses/new/page.tsx:28-52` — the canonical mutation handler
- `frontend/src/lib/risk.ts:20` — `RISK_LEVEL_LABEL`, the Polish-label map convention
- `backend/src/test/.../AnalysisIsolationTest.java:54-56` `persistUser`; `:63-66` entity construction; `:73-82` byte-identical 404
- `backend/src/test/.../AuthBoundaryMatrixTest.java:30-44` GET-only matrix; `:47-56` the POST pair to mirror for PATCH
- `backend/src/test/.../AnalysisFlowTest.java:121-129` `post(...).with(csrf()).with(user(...))`; `:187-197` lambda `ChatModel`; `:87-109` two-phase FK-safe cleanup
- `frontend/e2e/analysis-history.spec.ts:18-21` `Date.now()` unique ids; `:23-27` UI registration

## Architecture Insights

**Ownership is a query-shape, not a check.** The codebase never writes `if (analysis.getOwnerId() != currentUser)`. It makes the wrong row unfetchable. The corollary for S-02 is that the mutation gets its security *by construction*: load the `Analysis` with `findByIdAndOwnerId`, then find the clause within `analysis.getClauses()`. A clause belonging to someone else's analysis is unreachable because the parent load already returned `Optional.empty()`. Introducing a `ClauseRepository.findById(clauseId)` would silently discard this property — that is the single mistake most worth guarding against in review.

**"Not found" and "not yours" are deliberately indistinguishable.** Both are a bodiless `404`. The only `403` in the system is a CSRF failure. Preserving this on the write path is what turns an ownership bug into a non-event rather than an id-enumeration oracle.

**S-02 is the codebase's first mutation of a persisted entity.** Every write so far is an insert; `2026-07-08-analysis-history/research.md:48` records the convention as *"No public setters (read-only after construction)."* There is no prior art. The plan should consciously choose the mutation shape — a narrow `Clause.setUserDecision(ClauseDecision)` mutator (not a general-purpose setter set), or an aggregate-level `Analysis.decide(clauseId, decision)` method that keeps the invariant inside the aggregate root. The latter is more faithful to how the rest of the model is written.

**Layers that "just work" versus the one that doesn't.** The security *authorization* layer needs no change (`anyRequest().authenticated()`), CSRF needs no change (`csrf.spa()` covers PATCH), the frontend fetch wrapper needs no change (`apiFetch` handles non-GET), and the DB needs no change (column exists). The *CORS* layer is the lone exception, and it fails in the most confusing possible way — a preflight rejection that never reaches a breakpoint in any Java handler. Three separate archived research docs flagged this in advance.

## Historical Context (from prior changes)

- `context/archive/2026-07-06-analyze-and-save-contract/plan.md:31` — *"No per-clause decision status UI/endpoint — `Clause.userDecision` defaults to `PENDING`; mutating it is S-02."* The column was reserved for this slice on purpose.
- `context/archive/2026-07-06-analyze-and-save-contract/plan.md:214` — `ClauseResponse` was specified from the start with both `Long id` and `ClauseDecision userDecision`, so the read half round-trips today.
- `context/archive/2026-07-06-analyze-and-save-contract/research.md:125` — *"S-02 (per-clause status, likely PATCH/PUT) … will need this [CORS] list extended."*
- `context/archive/2026-07-06-testing-auth-boundary-regression/research.md:272` — *"When S-02/S-04 add PATCH/DELETE routes, both the matrix and the CORS method list must grow."*
- `context/archive/2026-07-08-analysis-history/research.md:98` — owner-scoping is *"a query-layer invariant … never by fetch-then-filter. A foreign or missing id both return an identical 404 (no existence leak)."*
- `context/archive/2026-07-08-analysis-history/research.md:48` — entities are *"read-only after construction"*, the convention S-02 must deliberately break.
- `context/archive/2026-07-08-analysis-history/reviews/impl-review.md` (F4) — all three pages' `useEffect` fetches lack unmount/abort guards; accepted as existing convention, **not** to be fixed piecemeal here.
- `context/archive/2026-07-08-analysis-history/reviews/impl-review.md` (F2) — the Playwright spec has no teardown until S-04 lands delete; use `Date.now()` ids and document the gap rather than building delete-adjacent surface for test hygiene.
- `context/archive/2026-07-04-identity-and-isolation/reviews/impl-review.md` (F1) and `2026-07-06-analyze-and-save-contract/reviews/impl-review.md` (F1) — the same bug twice: an unmapped `DataIntegrityViolationException` falls through to the *global* `AuthExceptionHandler` and surfaces as a nonsensical `409 "Email already in use"`. Do not let a DB constraint escape unmapped.
- `context/archive/2026-07-06-analyze-and-save-contract/reviews/impl-review.md` (F3) — a self-invoked `@Transactional` private method is a no-op under Spring's proxy AOP; that is why `createAnalysis` uses a `TransactionTemplate`. A public, externally-invoked `@Transactional` service method (which S-02 will have) is unaffected.
- `context/foundation/test-plan.md` §2 Risk #1 — *"User A requesting/**mutating** User B's analysis by id is refused (404/403, not 200)"*; §6.4 is the reusable cookbook. §6.6 warns `Analysis.clauses` has **no `@OrderBy`**, so tests must never assert a clause by list index.
- `context/foundation/test-plan.md` does not list S-02 as a named rollout phase (phases are auth-boundary ✓, classification+isolation ✓, frontend E2E, CI gate, eval), but §2 Risk #1 and §6.4 govern it directly.
- `context/foundation/roadmap.md:114` — *"Small vertical add on S-01's analysis view; the load-bearing care is that status changes stay owner-scoped (reuse F-01's invariant, don't reinvent it). Low risk."*

## Related Research

- `context/archive/2026-07-06-analyze-and-save-contract/research.md` — the origin of `Clause`, `ClauseDecision`, and the CORS forward-reference
- `context/archive/2026-07-08-analysis-history/research.md` — current frontend/API conventions (client components, `apiFetch`, no server actions)
- `context/archive/2026-07-06-testing-auth-boundary-regression/research.md` — the boundary-matrix pattern and its growth obligation
- `context/archive/2026-07-04-identity-and-isolation/plan.md` — `SecurityUtils`, the ownership primitive
- `context/foundation/test-plan.md` §6.4 — the isolation-test cookbook

## Open Questions

1. **PATCH vs PUT.** Genuinely undecided — every archived doc writes the pair "PATCH/PUT". `PATCH /api/analyses/{analysisId}/clauses/{clauseId}` fits a single-field partial update and matches the existing resource nesting. Whichever is chosen must be added to `SecurityConfig`'s CORS `setAllowedMethods`.
2. **Should `Analysis.status` advance `ANALYZED` → `REVIEWED`?** The enum defines `DRAFT` → `ANALYZED` → `REVIEWED` but only `ANALYZED` is ever written, and no doc specifies the transition. Does "reviewed" mean *every* clause is non-`PENDING`, or is it a separate explicit user action? FR-007 does not say. **This is a product question, not a technical one** — worth settling in `/10x-plan` or deferring explicitly.
3. **Is `PENDING` a settable value, or only an initial state?** FR-007 lists "accepted / to-negotiate / rejected". Should the request DTO reject `PENDING` (400), or accept it as an "undo" back to the default? Undo is the friendlier product behaviour and costs nothing.
4. **Aggregate method vs entity setter.** `Analysis.decide(clauseId, decision)` keeps the invariant inside the aggregate root and preserves "no public setters"; a bare `Clause.setUserDecision(...)` is simpler but opens the entity. No precedent exists either way.
5. **Which UI control?** No `select` / `radio-group` / `toggle-group` is installed, though the unified `radix-ui` package is a dependency. Options: add a shadcn wrapper via CLI, or build a segmented control from the existing `Button` variants. The latter adds zero dependencies.
6. **In-place update semantics.** Every existing mutation navigates away on success. A per-clause control needs optimistic local state (and rollback on `ApiError`) or a re-fetch — a new interaction shape with no precedent. Also: how is a failed decision surfaced, given no toast component exists?

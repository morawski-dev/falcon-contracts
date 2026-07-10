---
date: 2026-07-10T10:29:29+02:00
researcher: Mateusz Morawski
git_commit: d76bc718fadd3a79ed7646726081d4f6d36fe314
branch: main
repository: falcon-contracts
topic: "Delete an analysis (S-04) — owner-scoped hard delete across backend, frontend, and tests"
tags: [research, codebase, delete-analysis, analysis, cascade, liquibase, owner-scoping, cors, playwright]
status: complete
last_updated: 2026-07-10
last_updated_by: Mateusz Morawski
---

# Research: Delete an analysis (S-04)

**Date**: 2026-07-10T10:29:29+02:00
**Researcher**: Mateusz Morawski
**Git Commit**: d76bc718fadd3a79ed7646726081d4f6d36fe314
**Branch**: main
**Repository**: falcon-contracts

## Research Question

How should `delete-analysis` (roadmap S-04, PRD FR-010) be implemented — a user hard-deletes one of their saved analyses, owner-scoped, destructive, surfaced on **both** the history list (`/dashboard`) and the analysis detail page (`/analyses/[id]`) — given the existing entity graph, FK topology, security config, frontend conventions, and test harness?

Scope confirmed with the user: research both delete surfaces, and cover the full verification surface (backend integration tests **and** the Playwright e2e harness).

## Summary

The slice is small in code and sharp in failure modes. Every seam it needs already exists; nothing needs inventing. Five findings dominate:

1. **The FK topology is the whole story.** `clauses.analysis_id` and `negotiation_points.analysis_id` both cascade on delete. But `negotiation_points.clause_id → clauses.id` (`fk_negotiation_points_clause`) declares **no `onDelete`**, so Postgres applies `NO ACTION` — a referential check deferred to **end of statement**. This makes a *single-statement* delete of the `analyses` row safe, and makes Hibernate's *multi-statement* entity cascade unsafe. The two paths are not interchangeable, and the codebase already has empirical evidence of the failure (see finding 2).

2. **The existing test teardown is a confession.** `AnalysisFlowTest` and `ClassificationContractTest` run a **two-phase** `@AfterEach`: commit a transaction that nulls every `negotiationPoint.clauseId`, then a second transaction that deletes. That workaround exists precisely because deleting `clauses` rows while `negotiation_points` still reference them fails. Any delete implementation that lets Hibernate order the child deletes will reproduce this. Flagged forward a change ago as **F6** in S-01's impl-review, with the explicit note "when S-04 is planned, either null `clause_id` before delete or change the FK to `ON DELETE SET NULL`."

3. **Ownership is a query shape, never a check — and the codebase is unanimous.** `findByIdAndOwnerId(...).orElseThrow(404)` is the only idiom. Cross-user and missing-id are *deliberately indistinguishable* (both bodiless 404); there is no 403 for ownership anywhere — a 403 in this system only ever means a CSRF failure. A delete must inherit this by construction, not re-derive it. Beware: Spring Data's derived `deleteByIdAndOwnerId` is **not** a bulk statement (it selects then deletes entities), so it lands on the unsafe Hibernate path.

4. **Two silent, invisible blockers sit outside the Java handler.** `SecurityConfig` CORS `setAllowedMethods(List.of("GET","POST","PATCH"))` omits `DELETE` — the browser preflight fails before any breakpoint or server log fires. And MockMvc never sends an `Origin` header, so **no ordinary backend test catches this**; S-02 solved it with a dedicated `OPTIONS` preflight assertion, which S-04 must copy. Separately, an escaping `DataIntegrityViolationException` (exactly what an FK violation produces) is caught by the *global* `AuthExceptionHandler` and rendered as `409 "Email already in use"` — a nonsensical response that has already shipped as a review finding twice (F-01 F1, S-01 F1).

5. **The frontend has no confirmation and no toast primitive, and the list row is a trap.** `alert-dialog`, `dialog`, `dropdown-menu`, and `sonner`/`toast` are all **absent** from `components/ui/`. Every dashboard row is a `<Link>` wrapping the entire `<Card>`, so a nested delete `<button>` is both invalid HTML and a navigation trigger. The app is 100% client components — no server actions, no `revalidatePath`, no `router.refresh()` — so post-delete refresh is local state mutation or re-fetch, and reaching for Next cache APIs would be off-pattern.

Bonus: S-04 is the named unblocker for the two existing Playwright specs, both of which carry a literal "Known limitation" comment saying they cannot clean up the account/analysis they create because delete does not exist yet. **Playwright is not wired into CI**, so the regression gate must be the backend integration test.

## Detailed Findings

### Backend — entity graph and the cascade asymmetry

`Analysis` is the aggregate root and owns **both** child collections directly, with full cascade:

```java
// Analysis.java:43-47
@OneToMany(mappedBy = "analysis", cascade = CascadeType.ALL, orphanRemoval = true)
private List<Clause> clauses = new ArrayList<>();

@OneToMany(mappedBy = "analysis", cascade = CascadeType.ALL, orphanRemoval = true)
private List<NegotiationPoint> negotiationPoints = new ArrayList<>();
```

Crucially, `NegotiationPoint` is **not** a JPA child of `Clause`. It links to its clause through a plain scalar column, so Hibernate has *no knowledge* of the dependency:

```java
// NegotiationPoint.java:23-28
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "analysis_id", nullable = false)
private Analysis analysis;

@Column(name = "clause_id")   // scalar, nullable, NOT an association
private Long clauseId;
```

`clauseId` is nullable and may legitimately be `null` when the LLM's negotiation point matches no clause (`AnalysisService.matchClauseId`). At the ORM level `NegotiationPoint` is a *sibling* of `Clause`, not its child.

The database disagrees about who depends on whom (`002-create-analyses.yaml:109-134`):

| Constraint | Columns | `onDelete` |
|---|---|---|
| `fk_analyses_owner` | `analyses.owner_id → users.id` | — (none) |
| `fk_clauses_analysis` | `clauses.analysis_id → analyses.id` | **`CASCADE`** |
| `fk_negotiation_points_analysis` | `negotiation_points.analysis_id → analyses.id` | **`CASCADE`** |
| `fk_negotiation_points_clause` | `negotiation_points.clause_id → clauses.id` | **— (none → `NO ACTION`)** |

I read the raw YAML (`002-create-analyses.yaml:129-134`) rather than trust a summary here, because two sub-agents reported this constraint differently (one said `RESTRICT`, one hedged) and the answer changes the implementation. Liquibase's `addForeignKeyConstraint` with no `onDelete` key emits no `ON DELETE` clause, and Postgres's default is `NO ACTION` — **not** `RESTRICT`. The difference is precisely *when* the referential check runs:

- **`NO ACTION`** — check deferred to the **end of the statement**.
- **`RESTRICT`** — check fires **immediately**, and cannot be escaped by same-statement cleanup.

That single fact splits the two candidate implementations:

- **Single-statement delete** (`DELETE FROM analyses WHERE id=? AND owner_id=?`): Postgres runs both `ON DELETE CASCADE` paths as part of that one statement. By the time the `NO ACTION` check on `negotiation_points.clause_id` fires at end-of-statement, both the clause rows *and* the negotiation-point rows are already gone. **Safe.**
- **Hibernate entity cascade** (`repository.delete(analysis)` or the derived `deleteByIdAndOwnerId`): Hibernate emits a **separate `DELETE` statement per collection**, in property-declaration order — `clauses` (line 43) *before* `negotiationPoints` (line 46). The `NO ACTION` check at the end of the *clauses* statement still sees live `negotiation_points` rows pointing at the just-deleted clauses. **FK violation.**

This is not speculation. The repo already trips over it in test teardown (next section), and S-01's review recorded it as a forward-looking hazard for exactly this slice.

### Backend — service, repository, controller, security

Owner-scoping is expressed as a query predicate, uniformly:

```java
// AnalysisService.java:75-76 (getAnalysis) — the canonical template
analysisRepository.findByIdAndOwnerId(id, ownerId)
    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
```

Because ownership is folded into the `WHERE` clause, a foreign id yields `Optional.empty()` and is *indistinguishable* from a missing id. There is no 403 branch anywhere in the domain. `AnalysisRepository` (`AnalysisRepository.java:11-18`) exposes **only** owner-scoped finders — `findByIdAndOwnerId`, `findSummariesByOwnerId` — and there is deliberately no `ClauseRepository`, because a bare `findById` on an owned entity would silently discard the invariant.

Transactions: reads are `@Transactional(readOnly = true)` (`AnalysisService.java:70`); the S-02 mutation is a plain `@Transactional` public method invoked from the controller (`AnalysisService.java:80`), which is the correct shape for delete. (`createAnalysis` uses a programmatic `TransactionTemplate` instead — because the LLM call must run *outside* the transaction, and because a `@Transactional` annotation on a self-invoked method is a silent no-op under Spring's proxy AOP. Delete is controller→service, so the annotation applies.)

The controller (`AnalysisController.java:24-54`) is `@RequestMapping("/api/analyses")`; the current principal is always `@AuthenticationPrincipal AppUserDetails principal` → `principal.getId()`, never `SecurityContextHolder`, never a body field. The closest template for a `DELETE` is the S-02 clause PATCH (`AnalysisController.java:50-54`). Convention forces the route `DELETE /api/analyses/{id}`; `204 No Content` matches the existing logout response.

Security (`SecurityConfig.java:31-77`):

- CSRF is **enabled** in SPA/cookie mode (`.csrf(csrf -> csrf.spa())`, line 36). `DELETE` is mutating, so it requires `X-XSRF-TOKEN`. The frontend's `apiFetch` already attaches it to any non-GET/HEAD, so no client wrapper change is needed beyond the existing `await getCsrf()` priming idiom.
- Sessions are **stateful** (`HttpSessionSecurityContextRepository`, JSESSIONID) — not JWT.
- `.anyRequest().authenticated()` (line 42) auto-gates the new route. No authorization rule to add, no `@PreAuthorize` (method security is not enabled).
- **`setAllowedMethods(List.of("GET", "POST", "PATCH"))` (line 75) omits `DELETE`.** The browser preflight will reject the call before any Java code runs.

Error handling: `AnalysisExceptionHandler` maps only `AnalysisFailedException → 502`. `AuthExceptionHandler` maps `AuthenticationException → 401` and **`DataIntegrityViolationException → 409 "Email already in use"`** — a global mapping written for the register flow. An FK violation escaping a delete would surface through it as that exact nonsensical 409. The same class of bug (unmapped DB constraint reaching a global handler) was a review finding in **both** F-01 and S-01.

### Frontend — surfaces, mutation pattern, missing primitives

Route map: `/dashboard` (`dashboard/page.tsx`) **is** the history list — there is no `/analyses` index route, a deliberate S-03 decision. Detail is `/analyses/[id]` (`analyses/[id]/page.tsx`), reading the segment via `useParams`. Every real page is a **client component**. There are no route handlers, no server actions, no server-side data fetching anywhere in the repo.

All backend calls go browser→Spring directly through one wrapper, `lib/api.ts`:

```ts
// api.ts — always credentials:"include"; injects X-XSRF-TOKEN from the XSRF-TOKEN cookie on any non-GET/HEAD
// throws ApiError(status, message) on !response.ok
```

`frontend/src/proxy.ts` is Next 16's renamed middleware — a **pure route guard** (checks `JSESSIONID` presence, redirects to `/login`), matching `/dashboard/:path*` and `/analyses/:path*`. It is not an API proxy, which is exactly why CORS is load-bearing.

The mutation template is S-02's clause decision (`analyses/[id]/page.tsx:45-90` + `lib/analyses.ts:73-84`): capture the prior value, apply an optimistic `useState` update, `await getCsrf()`, call the typed wrapper, and on failure **roll back** and write an inline per-item error. `401 → router.push("/login")`. There is **no `router.refresh()`, no `revalidatePath`, no SWR/react-query** — data loads in a mount `useEffect` and is mutated in local state.

Mapped onto delete: add `deleteAnalysis(id)` to `lib/analyses.ts`; on the **detail** page navigate to `/dashboard` on success; on the **list** page drop the row from local state (`setAnalyses(prev => prev?.filter(a => a.id !== id) ?? prev)`).

Two frontend obstacles, both net-new work:

- **The list row is one big anchor.** `dashboard/page.tsx:97-109` renders each analysis as `<Link href={/analyses/${id}}>` wrapping the whole `<Card>`; `CardContent` is `flex items-center justify-between` with title+date left and a status `<Badge>` right. There is no per-row action area. A delete button nested inside that anchor is invalid HTML (interactive-in-anchor), an a11y problem, and will navigate on click. Either the row is restructured so the button is a *sibling* of the link, or the click is intercepted. No prior change solved this.
- **No confirmation or toast primitive exists.** Present in `components/ui/`: `button`, `input`, `label`, `card`, `textarea`, `badge`, `alert`, `separator`, `skeleton`. **Absent:** `alert-dialog`, `dialog`, `dropdown-menu`, `sonner`/`toast`. There is no `window.confirm` and no destructive-action pattern anywhere; all feedback today is inline `text-destructive` text. `button` does ship an unused `destructive` variant. Given the roadmap's "hard to trigger by accident" requirement, `alert-dialog` is the natural (and currently missing) addition.

UI copy is hardcoded Polish inline in JSX; only enum label maps are centralized (`CLAUSE_DECISION_LABEL`, `ANALYSIS_STATUS_LABEL` in `lib/analyses.ts`; `RISK_*_LABEL` in `lib/risk.ts`). Tone is informal-imperative, in-progress states use `…`, errors end with "Spróbuj ponownie." New delete copy should be written inline to match.

### Verification — what exists, what CI actually runs

Backend tests are all full `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)` against a real Postgres 18 container wired by `@ServiceConnection`. There are **no `@DataJpaTest` and no `@WebMvcTest` slice tests** (a bare `@DataJpaTest` fails — no embedded DB on the classpath) and **no shared base test class**; each test opts in individually. `src/test/resources/application.properties` sets `ddl-auto=validate`, so **Liquibase owns the schema and any migration mistake fails the whole context at boot**.

Auth in tests is faked with `.with(user(new AppUserDetails(u)))` and, for mutations, `.with(csrf())` — never `@WithMockUser`. Omitting `.with(csrf())` yields a misleading 403.

The precise template for "A cannot delete B's analysis" already exists for PATCH:

```java
// AnalysisIsolationTest.java:76-85
assertThat(crossUserResult.getResponse().getStatus())
    .as("wrong-owner id must be indistinguishable from a missing id")
    .isEqualTo(404)
    .isEqualTo(missingIdResult.getResponse().getStatus());
assertThat(crossUserResult.getResponse().getContentAsString())
    .isEqualTo(missingIdResult.getResponse().getContentAsString()); // both empty
```

A delete test mirrors this and adds one assertion the PATCH version doesn't need: **A's analysis must still exist afterwards.**

Seeding needs no LLM mock — `AnalysisIsolationTest` never mocks `ChatModel`. Seed through the repository; the `Clause`/`NegotiationPoint` constructors **self-register into the parent's collection**:

```java
// AnalysisIsolationTest.java:66-69
Analysis analysis = new Analysis(ownerA.getId(), "Umowa A", "Tresc umowy...", AnalysisStatus.ANALYZED, Instant.now());
new Clause(analysis, "Automatyczne przedluzenie...", RiskLevel.HIGH, RiskType.AUTO_RENEWAL, "...uzasadnienie...");
Analysis saved = analysisRepository.save(analysis); // cascade-saves the clause
```

And the teardown that proves finding 1 — `AnalysisFlowTest.java:88-110` commits a transaction nulling every `negotiationPoint.clauseId`, *then* a second transaction that deletes. That two-phase dance exists solely because `fk_negotiation_points_clause` has no cascade and is not a JPA relationship. Tests that seed clauses without linked negotiation points (the isolation tests) get away with a single-phase `deleteAll()`.

Playwright **exists** (contrary to older docs): `frontend/playwright.config.ts`, `testDir: "./e2e"`, chromium only, `baseURL` from `PLAYWRIGHT_BASE_URL`. There is **no `webServer` block** — specs assume a live backend hitting the **real OpenRouter LLM**. No `storageState`, no global setup; each spec registers a fresh account through the UI and suffixes emails/titles with `Date.now()`. Locators follow the CLAUDE.md rules (`getByRole`/`getByLabel`, waits on state, no `waitForTimeout`).

Both specs carry the same comment, which is a direct handoff to this slice:

> "S-04 (delete-analysis) is not built yet, so there is no UI path to remove the user/analysis this test creates" — `e2e/analysis-history.spec.ts:9-11`, `e2e/clause-decision.spec.ts:17-19`

**CI gap (critical):** `.github/workflows/ci.yml` runs `./mvnw -B -ntp clean package` for the backend (so a new backend test runs automatically), but for the frontend it runs **only `pnpm lint` and `pnpm build` — never `pnpm test:e2e`**. Playwright is not in CI and could not be without a `webServer` + live-LLM setup the config deliberately omits. `test-plan.md` confirms Phases 3 and 4 (browser E2E, CI quality gates) are "not started." **Any claim that "CI will catch a delete regression" must rest on the backend integration test.**

`test-plan.md` also governs the layering: cross-user isolation is **Risk #1, "protect-first"**, its must-prove bar is "User A requesting/**mutating** User B's analysis by id is refused (404/403, not 200)", and its cheapest-signal layer is explicitly "backend integration with two distinct users" (`test-plan.md:53,80,178-183`). Delete's correctness belongs there, not in the browser.

## Code References

Permalinks are pinned to `d76bc71` (pushed to `origin/main`).

- [`backend/src/main/java/com/morawski/dev/falcon/analysis/Analysis.java:43-47`](https://github.com/morawski-dev/falcon-contracts/blob/d76bc718fadd3a79ed7646726081d4f6d36fe314/backend/src/main/java/com/morawski/dev/falcon/analysis/Analysis.java#L43-L47) — both child collections `cascade = ALL, orphanRemoval = true`; `clauses` declared *before* `negotiationPoints` (the Hibernate delete order).
- [`backend/src/main/java/com/morawski/dev/falcon/analysis/NegotiationPoint.java:27-28`](https://github.com/morawski-dev/falcon-contracts/blob/d76bc718fadd3a79ed7646726081d4f6d36fe314/backend/src/main/java/com/morawski/dev/falcon/analysis/NegotiationPoint.java#L27-L28) — `clauseId` is a nullable scalar `@Column`, **not** an association.
- [`backend/src/main/resources/db/changelog/changes/002-create-analyses.yaml:129-134`](https://github.com/morawski-dev/falcon-contracts/blob/d76bc718fadd3a79ed7646726081d4f6d36fe314/backend/src/main/resources/db/changelog/changes/002-create-analyses.yaml#L129-L134) — `fk_negotiation_points_clause` with **no `onDelete`** → Postgres `NO ACTION`.
- [`backend/src/main/resources/db/changelog/changes/002-create-analyses.yaml:115-128`](https://github.com/morawski-dev/falcon-contracts/blob/d76bc718fadd3a79ed7646726081d4f6d36fe314/backend/src/main/resources/db/changelog/changes/002-create-analyses.yaml#L115-L128) — both `analysis_id` FKs declare `onDelete: CASCADE`.
- `backend/src/main/resources/db/changelog/db.changelog-master.yaml` — `include:` wiring; next free changeset is `004-*`, `author: falcon`, id = filename stem.
- [`backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisService.java:70-91`](https://github.com/morawski-dev/falcon-contracts/blob/d76bc718fadd3a79ed7646726081d4f6d36fe314/backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisService.java#L70-L91) — `findByIdAndOwnerId(...).orElseThrow(404)`; `@Transactional` mutation shape.
- [`backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisRepository.java:11-18`](https://github.com/morawski-dev/falcon-contracts/blob/d76bc718fadd3a79ed7646726081d4f6d36fe314/backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisRepository.java#L11-L18) — only owner-scoped finders; **no delete method today**.
- [`backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisController.java:50-54`](https://github.com/morawski-dev/falcon-contracts/blob/d76bc718fadd3a79ed7646726081d4f6d36fe314/backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisController.java#L50-L54) — the PATCH endpoint, closest template for `DELETE /api/analyses/{id}`.
- [`backend/src/main/java/com/morawski/dev/falcon/auth/SecurityConfig.java:75`](https://github.com/morawski-dev/falcon-contracts/blob/d76bc718fadd3a79ed7646726081d4f6d36fe314/backend/src/main/java/com/morawski/dev/falcon/auth/SecurityConfig.java#L75) — `setAllowedMethods(List.of("GET","POST","PATCH"))`; **`DELETE` missing**.
- [`backend/src/main/java/com/morawski/dev/falcon/auth/SecurityConfig.java:36`](https://github.com/morawski-dev/falcon-contracts/blob/d76bc718fadd3a79ed7646726081d4f6d36fe314/backend/src/main/java/com/morawski/dev/falcon/auth/SecurityConfig.java#L36) — `.csrf(csrf -> csrf.spa())`; DELETE needs `X-XSRF-TOKEN`.
- `backend/src/main/java/com/morawski/dev/falcon/auth/AuthExceptionHandler.java` — global `DataIntegrityViolationException → 409 "Email already in use"`; an FK violation would surface here.
- `backend/src/test/java/com/morawski/dev/falcon/analysis/AnalysisIsolationTest.java:62-117` — cross-user vs missing-id byte-identical 404, for GET and PATCH. The delete test's template.
- `backend/src/test/java/com/morawski/dev/falcon/analysis/AnalysisFlowTest.java:88-110` — the two-phase `@AfterEach` that nulls `clause_id` before deleting. Empirical proof of the FK ordering hazard.
- `backend/src/test/java/com/morawski/dev/falcon/analysis/AnalysisRepositoryTest.java:40-56` — full `User→Analysis→Clause→NegotiationPoint` seeding via self-registering constructors.
- `backend/src/test/java/com/morawski/dev/falcon/auth/AuthBoundaryMatrixTest.java:61-70` — anon mutating verb without CSRF → 403, with CSRF → 401. Its `@MethodSource` covers **GETs only**; a DELETE needs hand-written tests.
- `backend/src/test/resources/application.properties` — `ddl-auto=validate`; this file *shadows* the main one, so JPA settings are duplicated there deliberately.
- `frontend/src/app/dashboard/page.tsx:97-109` — each row is a `<Link>` wrapping the whole `<Card>`; no per-row action area.
- `frontend/src/app/analyses/[id]/page.tsx:45-90` — optimistic-update + rollback + inline error, the mutation template.
- `frontend/src/lib/analyses.ts:73-84` — `updateClauseDecision`: `getCsrf()` then `apiFetch`. Where `deleteAnalysis(id)` goes.
- `frontend/src/lib/api.ts` — `credentials:"include"`, XSRF header injection on non-GET, `ApiError(status, message)`.
- `frontend/src/proxy.ts:5-13` — Next 16 renamed middleware; JSESSIONID route guard for `/dashboard/*` and `/analyses/*`.
- `frontend/e2e/analysis-history.spec.ts:9-11`, `frontend/e2e/clause-decision.spec.ts:17-19` — "Known limitation" comments naming S-04 as the teardown unblocker.
- `.github/workflows/ci.yml` — backend `clean package` (runs tests); frontend `lint` + `build` only, **no `test:e2e`**.

## Architecture Insights

- **Ownership is a query shape, not a check.** Every owned read goes through a repository method that takes `ownerId` and folds it into the `WHERE` clause. The consequence is that authorization failures are *unrepresentable* as a distinct state: a foreign id and a missing id produce the same empty `Optional`, the same 404, the same empty body. Introducing any bare `findById`/`deleteById` on an owned entity silently discards this property — which is why there is no `ClauseRepository`. This is the single most valuable invariant in the codebase and the one most easily broken by a "convenient" repository method.

- **Two cascade systems overlap but do not agree.** The ORM believes `Analysis` owns two independent sibling collections. The database believes `negotiation_points` additionally depends on `clauses`. Neither model is wrong; they are *different graphs over the same rows*, and the delete path must satisfy both. `NO ACTION`'s end-of-statement check is the seam that makes one-statement deletion work and multi-statement deletion fail — a distinction invisible in the entity code and only legible in the DDL.

- **Spring Data's derived `deleteBy…` is a false friend here.** It reads like a bulk statement and behaves like an entity cascade (select, then `delete(entity)` per row). Choosing it because it *names* the owner-scoping would land squarely on the unsafe path — the naming convention and the execution semantics point in opposite directions.

- **The most dangerous failure modes in this slice are all invisible to the tests that would normally catch them.** MockMvc sends no `Origin`, so no standard backend test sees the missing CORS `DELETE`. `@SpringBootTest` is not auto-rolled-back, so teardown bugs masquerade as test-ordering flakes. And an FK violation doesn't surface as an FK error — it surfaces as `409 "Email already in use"` through a global handler written for the registration flow. Each needs a *deliberate* test, not a generic one.

- **The frontend is uniformly client-side by necessity, not preference.** `apiFetch` reads `document.cookie` for the CSRF token, so it cannot run on the server. That single constraint is why there are no server actions, no `revalidatePath`, and why the browser calls Spring cross-origin — which in turn is why CORS is load-bearing rather than incidental.

## Historical Context (from prior changes)

- `context/archive/2026-07-06-analyze-and-save-contract/reviews/impl-review.md:73-81` — **F6**, deferred to this slice verbatim: "`negotiation_points.clause_id → clauses.id` FK has no cascade (forward-looking note for S-04)… When S-04 is planned, either null `clause_id` before delete or change the FK to `ON DELETE SET NULL`." `AnalysisFlowTest`'s cleanup nulls `clause_id` "for this exact reason."
- `context/archive/2026-07-09-clause-decision-status/research.md:60,128,130` — "'not found' and 'not yours' collapse into one identical `404`. There is no `403` for ownership anywhere. A `403` only ever means a CSRF failure." And: "Ownership is a query-shape, not a check… that is the single mistake most worth guarding against in review."
- `context/archive/2026-07-04-identity-and-isolation/plan.md:45` — the invariant, verbatim: "repository methods that return owned data take an `ownerId` parameter… there is no bare `findById` for owned entities." Enforcement is query-level; there are no `@PreAuthorize` guards and method security is not enabled.
- `context/archive/2026-07-06-analyze-and-save-contract/research.md:125` and `context/archive/2026-07-06-testing-auth-boundary-regression/research.md:233,272` — the CORS method list was flagged **three separate times** as needing `DELETE` when S-04 lands.
- `context/archive/2026-07-09-clause-decision-status/plan.md:169-179` — S-02 added a dedicated `OPTIONS` preflight test asserting the verb appears in `Access-Control-Allow-Methods`, described as "the only automated guard on the CORS list." S-04 should add the `DELETE` analogue.
- `context/archive/2026-07-09-clause-decision-status/reviews/impl-review.md:29-47` — the **F1/F2** fixes referenced in the git log. **F1**: `Analysis.decide()` called `clause.getId().equals(clauseId)` and NPE'd on unsaved entities with null ids; fixed by putting the guaranteed-non-null operand first. **F2**: the Playwright clause locator could match multiple groups because `Analysis.clauses` has **no `@OrderBy`** — never address list items by index or order.
- `context/archive/2026-07-09-clause-decision-status/plan.md:44-52` — S-02's out-of-scope list ends with "No e2e cleanup / teardown — **Blocked until S-04 ships delete**."
- `context/archive/2026-07-08-analysis-history/plan.md:39,42` — the list deliberately lives on `/dashboard`, not a separate `/analyses` route; "**No delete action** — that is S-04, a separate slice."
- `context/archive/2026-07-08-analysis-history/reviews/impl-review.md:40-48` — **F2**: the history Playwright spec has no cleanup; "revisit this test once S-04 (delete-analysis) lands and add real teardown then."
- `context/archive/2026-07-04-identity-and-isolation/reviews/impl-review.md:23-31` and `context/archive/2026-07-06-analyze-and-save-contract/reviews/impl-review.md:23-31` — **the same bug twice**: an unmapped `DataIntegrityViolationException` reaching the global handler and rendering as `409 "Email already in use"`. Do not let a DB constraint escape unmapped.
- `context/foundation/prd.md:87` — the NFR that settles hard-vs-soft delete: "A user's saved analyses persist until that user deletes them; deletion is user-initiated and **removes** the analysis (no automatic expiry in the MVP)."
- `context/foundation/roadmap.md:129-139` — S-04's framing: "Destructive and privacy-relevant — deletion must be owner-scoped and **hard to trigger by accident**."
- `context/foundation/test-plan.md:53,80,170,178-183` — cross-user isolation is Risk #1 ("protect-first"); the must-prove bar covers **mutating** requests; cheapest signal is "backend integration with two distinct users"; `@SpringBootTest` tests are not auto-rolled-back, so clean up explicitly.

## Related Research

- `context/archive/2026-07-09-clause-decision-status/research.md` — the immediately preceding mutation slice; its backend/frontend/testing sections are the closest structural analogue to this one.
- `context/archive/2026-07-08-analysis-history/research.md` — the list page and `GET /api/analyses` projection that the delete affordance attaches to.
- `context/archive/2026-07-06-testing-auth-boundary-regression/research.md` — the anon/CSRF/CORS boundary matrix a `DELETE` route must extend.
- `context/archive/2026-07-06-analyze-and-save-contract/research.md` — the aggregate's construction, and the origin of the `clause_id` scalar decision.

## Open Questions

These are design forks left for `/10x-plan`. Each has a recommendation, but each genuinely changes the implementation.

1. **How is the delete statement issued?** This is the load-bearing decision, because the FK topology makes the two options behave differently.
   - **(a) Bulk owner-scoped delete** — `@Modifying @Query("delete from Analysis a where a.id = ?1 and a.ownerId = ?2")` returning the affected-row count; `0 → 404`. One SQL statement, so Postgres's `ON DELETE CASCADE` clears both children and the `NO ACTION` check passes at end-of-statement. No entity loading, no lazy-init, no ordering hazard. Bypasses the persistence context (harmless for a terminal operation).
   - **(b) Load-then-`delete(entity)`** — reuses `findByIdAndOwnerId(...).orElseThrow(404)` and lets Hibernate cascade. Reads most idiomatically, matches every other service method — **and lands on the unsafe multi-statement path** unless the FK is changed first.
   - **Recommendation:** (a). It satisfies both cascade graphs today with no migration, and the `0-rows-affected → 404` shape preserves the "foreign id is indistinguishable from missing id" invariant exactly. If (b) is preferred for idiomatic consistency, it **must** be paired with question 2's migration — and even then, verify Hibernate's emitted statement order rather than assuming it.

2. **Should changeset `004-*` alter `fk_negotiation_points_clause` to `ON DELETE SET NULL`?** S-01's F6 explicitly deferred this here. It is *not required* by option 1(a). It **is** required by 1(b). Independently, it would let the two-phase `@AfterEach` teardowns in `AnalysisFlowTest`/`ClassificationContractTest` collapse to a single `deleteAll()`, retiring a workaround that currently encodes a schema smell. **Recommendation:** yes, ship it — the cost is one changeset, and it removes a hazard that has already forced a workaround and been flagged in review. It also makes the *future* case of deleting an individual clause safe, which is currently a live foot-gun. Note `ddl-auto=validate` means a malformed changeset fails the entire test context at boot, so this lands early and loudly.

3. **What HTTP status does a successful delete return — `204 No Content` or `200`?** `204` matches the existing logout response and needs no DTO. **Recommendation:** `204`, `ResponseEntity<Void>`.

4. **Is the delete idempotent?** Deleting an already-deleted (or foreign) id returns 404 under both options, which is consistent with every other endpoint and preserves the isolation invariant. The alternative (`204` on a no-op) would leak nothing but would break the "byte-identical to missing" symmetry the isolation tests assert. **Recommendation:** 404, and assert byte-identity with the missing-id response as `AnalysisIsolationTest` does.

5. **How is the list-row anchor conflict resolved?** Either (a) restructure the row so the `<Link>` wraps only the title/meta and the delete control sits in a sibling action cell, or (b) keep the full-card link and `preventDefault()/stopPropagation()` on the button. (a) is correct HTML and better a11y; (b) is a smaller diff. **Recommendation:** (a) — the roadmap's "hard to trigger by accident" requirement argues against a destructive control nested inside a navigation target.

6. **Does the confirmation step use shadcn `alert-dialog`, or a plain inline two-step confirm?** `alert-dialog` is absent and would be the first dialog primitive in the repo (one CLI add, a Radix dependency). S-02's "What We're NOT Doing" explicitly avoided adding a shadcn component. **Recommendation:** add `alert-dialog` — "destructive and hard to trigger by accident" is precisely its purpose, and hand-rolling a focus-trapped modal is strictly worse. Skip `sonner`/toast; match the existing inline-error convention.

7. **Does this slice also retrofit e2e teardown into the two existing specs?** Both specs' "Known limitation" comments name S-04 as the unblocker, so it is the natural moment — but Playwright is **not in CI**, so this is hygiene, not a regression gate, and it expands the diff. **Recommendation:** add a delete e2e spec and retrofit the two existing specs' teardown, but keep the *verification* claim resting on the backend integration test, and say so explicitly in the plan's Testing Strategy.

8. **Which deliberate tests guard the invisible failures?** At minimum: a `DELETE` CORS preflight assertion (nothing else catches the missing verb); hand-written anon-`DELETE` cases (`without csrf → 403`, `with csrf → 401`) since `AuthBoundaryMatrixTest`'s `@MethodSource` is GET-only; a cross-user delete asserting 404 **and** that the victim's analysis survives; and an owner-delete asserting all three tables are empty for that id — not merely that the `analyses` row is gone.

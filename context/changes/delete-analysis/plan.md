# Delete an analysis (S-04) Implementation Plan

## Overview

Let a logged-in user hard-delete one of their saved analyses. The delete is owner-scoped by query shape (not by a check), issued as a **single SQL statement** so that both the ORM's and the database's cascade graphs are satisfied, and surfaced behind a confirmation dialog from **both** the dashboard list and the analysis detail page.

This is roadmap slice **S-04** / PRD **FR-010**. It is the MVP's entire retention mechanism: analyses persist until their owner removes them, with no automatic expiry.

## Current State Analysis

Every seam this feature needs already exists. Nothing needs inventing; the risk is entirely in the details.

- **The aggregate.** `Analysis` owns two child collections directly — `clauses` and `negotiationPoints` — both `cascade = ALL, orphanRemoval = true` (`Analysis.java:43-47`). `NegotiationPoint` links to its clause through a plain nullable scalar `@Column(name = "clause_id")`, **not** a JPA association (`NegotiationPoint.java:27-28`). At the ORM level the two children are siblings, not parent and child.
- **The database disagrees.** `clauses.analysis_id` and `negotiation_points.analysis_id` both declare `onDelete: CASCADE`. But `fk_negotiation_points_clause` (`002-create-analyses.yaml:129-134`) declares **no `onDelete` at all**, so Postgres applies `NO ACTION` — a referential check deferred to **end of statement**.
- **There is no delete anywhere.** `AnalysisRepository` (`AnalysisRepository.java:11-18`) exposes only owner-scoped finders. `AnalysisController` (`AnalysisController.java:24-54`) has POST, GET list, GET by id, and the S-02 clause PATCH. No `@DeleteMapping`. `lib/analyses.ts` has no `deleteAnalysis`.
- **Ownership is a query shape.** `findByIdAndOwnerId(...).orElseThrow(() -> new ResponseStatusException(NOT_FOUND))` (`AnalysisService.java:75-76`) is the only idiom. A foreign id and a missing id both yield an empty `Optional` and an identical bodiless 404. There is no 403 for ownership anywhere in the domain; a 403 in this system only ever means a CSRF failure.
- **CORS omits `DELETE`.** `SecurityConfig.java:75` allows `GET, POST, PATCH`. The browser preflight will reject a `DELETE` before any Java handler runs.
- **A global handler misroutes DB violations.** `AuthExceptionHandler` (`AuthExceptionHandler.java:30-33`) is an **unscoped** `@RestControllerAdvice` mapping *any* `DataIntegrityViolationException` to `409 "Email already in use"`. Its own Javadoc says it exists for the registration `existsByEmail()`/`save()` race — but it catches constraint violations from every controller in the app.
- **The dashboard row is one big anchor.** `dashboard/page.tsx:97-109` renders each analysis as a `<Link>` wrapping the entire `<Card>`; the right-hand side holds only a status `<Badge>`. There is no per-row action area.
- **No dialog primitive exists.** `components/ui/` holds `button`, `input`, `label`, `card`, `textarea`, `badge`, `alert`, `separator`, `skeleton`. `alert-dialog`, `dialog`, `dropdown-menu`, and any toast are all absent. `button` ships an unused `destructive` variant.
- **Tests are all full `@SpringBootTest` + Testcontainers**; `ddl-auto=validate` means Liquibase owns the schema and a bad changeset fails the entire context at boot. `AuthBoundaryMatrixTest`'s `@MethodSource` covers **GETs only**. MockMvc sends no `Origin` header, so no ordinary test sees a missing CORS verb.
- **Both e2e specs are waiting on this slice.** `e2e/analysis-history.spec.ts:9-11` and `e2e/clause-decision.spec.ts:17-19` each carry a "Known limitation" comment: *"S-04 (delete-analysis) is not built yet, so there is no UI path to remove the user/analysis this test creates."*

## Desired End State

A logged-in user sees a delete control on each dashboard row and on the analysis detail page. Activating it opens a confirmation dialog in Polish; confirming removes the analysis and all of its clauses and negotiation points from the database, the row disappears from the list (or the detail page redirects to `/dashboard`), and the analysis is unreachable thereafter. Another user attempting to delete it receives a response byte-identical to one for an id that never existed, and the analysis survives untouched.

**Verification:** `./mvnw clean package` passes with new tests proving the owner-delete empties all three tables, the cross-user delete is a no-op returning 404, the anon delete is rejected, and `DELETE` appears in the CORS preflight response. `pnpm lint && pnpm build` pass. Manually: delete from both surfaces, confirm the dialog resists an accidental click, and confirm a second browser session logged in as a different user cannot delete the first user's analysis.

### Key Discoveries:

- `fk_negotiation_points_clause` has **no `onDelete`** (`002-create-analyses.yaml:129-134`) → Postgres `NO ACTION`, checked at **end of statement**, not immediately. `RESTRICT` would be checked immediately; the distinction decides the implementation.
- `negotiation_points.clause_id` is **nullable** (`002-create-analyses.yaml:96-98` — no `constraints` block), so `ON DELETE SET NULL` is a valid migration target.
- Hibernate emits a separate `DELETE` per collection in **field-declaration order** — `clauses` (`Analysis.java:43`) before `negotiationPoints` (`Analysis.java:46`) — so a `repository.delete(entity)` cascade deletes clauses while negotiation points still reference them.
- `AnalysisFlowTest.java:88-110` runs a **two-phase `@AfterEach`**: commit a transaction nulling every `negotiationPoint.clauseId`, then a second transaction that deletes. That workaround is the codebase's own evidence for the hazard above.
- **`deleteByIdAndOwnerId` is a false friend.** Spring Data's derived `deleteBy…` selects, then calls `delete(entity)` per row — it *reads* like a bulk statement and *executes* as an entity cascade. Its name encodes the owner-scoping, which makes it look like exactly the right tool; it is not.
- S-01's impl-review filed this forward as **F6**: *"When S-04 is planned, either null `clause_id` before delete or change the FK to `ON DELETE SET NULL`."* (`context/archive/2026-07-06-analyze-and-save-contract/reviews/impl-review.md:73-81`)
- S-02 established the CORS preflight test as *"the only automated guard on the CORS list"* (`context/archive/2026-07-09-clause-decision-status/plan.md:169-179`).
- The unmapped-`DataIntegrityViolationException`-to-409 bug has now been a review finding **twice** (F-01 F1, S-01 F1).
- `AuthController` exists at `auth/AuthController.java`, so `@RestControllerAdvice(assignableTypes = AuthController.class)` is a valid narrowing mechanism.
- `lib/analyses.ts:2` already imports `getCsrf` from `@/lib/auth`; every mutation calls `await getCsrf()` before `apiFetch`.

## What We're NOT Doing

- **No soft delete / archive flag.** PRD NFR (`prd.md:87`): *"deletion is user-initiated and **removes** the analysis."* Hard delete, no tombstone, no undo.
- **No bulk / multi-select delete.** One analysis at a time.
- **No toast/`sonner` system.** Errors stay inline `text-destructive`, matching the existing convention.
- **No `dropdown-menu`** — the delete button sits directly in the row, not behind a `⋯` menu.
- **No cascade-delete of a `User`** and their analyses. Account deletion is not in the MVP.
- **No simplification of the existing two-phase `@AfterEach` teardowns** in `AnalysisFlowTest` / `ClassificationContractTest`. Changeset 004 makes them unnecessary, but they are now merely *redundant* — they still pass, and they still clean up correctly. Contrast Phase 4's e2e retrofit, which touches files outside this feature for the opposite reason: those two specs are *broken without S-04* and say so in their own comments. "Still works but is now superfluous" is a future cleanup; "cannot function until this slice lands" is this slice's responsibility.
- **No wiring of Playwright into CI.** That is `test-plan.md`'s Phase 4 and remains unstarted. This plan's regression gate is the backend integration test — see Testing Strategy.
- **No `Analysis.status` transition** to `REVIEWED`. Still unwritten, still out of scope.
- **No `@OrderBy` on `Analysis.clauses`.** The ordering instability is real (S-02 F2) but orthogonal to deletion.
- **No `ClauseRepository` / `NegotiationPointRepository`.** Their absence is F-01's invariant, not an oversight. Phase 2's row-count assertions use a test-scoped `JdbcTemplate` rather than creating production repositories to serve a test.

## Implementation Approach

Issue the delete as a **single bulk `@Modifying` JPQL statement scoped by both id and owner**, returning the affected-row count. `0 → 404`. This is the load-bearing decision, and it works because of a timing property rather than a structural one:

`DELETE FROM analyses WHERE id = ? AND owner_id = ?` triggers both `ON DELETE CASCADE` paths as part of that one statement. When the `NO ACTION` check on `negotiation_points.clause_id` fires at end-of-statement, the clause rows *and* the negotiation-point rows are already gone, so the check passes. No entity is loaded, no lazy collection is initialized, and Hibernate never gets the chance to order the child deletes wrongly.

The affected-row count doubles as the authorization result. Because `owner_id` is a `WHERE` predicate, a foreign id deletes zero rows — indistinguishable from an id that never existed. The 404 falls out of the query shape rather than being asserted by a check, which is exactly how every other owned read in this codebase enforces isolation.

Changeset `004` then flips `fk_negotiation_points_clause` to `ON DELETE SET NULL`. This is **not required** by the bulk-delete path — it is paying down F6 where it was filed. It defuses the currently-live foot-gun of deleting an individual clause (which would fail today), and it renders the two-phase test teardowns redundant.

Narrowing `AuthExceptionHandler` to `AuthController` is defense in depth. With the bulk delete *and* changeset 004 both landed, an FK violation from this feature is unreachable. But the handler's blast radius is the whole application, and a DB constraint violation anywhere currently answers `409 "Email already in use"` — a response that actively misleads during debugging, and a bug class already filed twice.

## Critical Implementation Details

**Ordering.** Phase 1 must land before Phase 2's tests are meaningful, because `ddl-auto=validate` means a malformed changeset fails the *entire* Spring context at boot — every test in the suite goes red at once, with an error that points at Liquibase rather than at the feature. Landing the schema change alone, against the existing green suite, keeps attribution clean.

**The bulk delete bypasses the persistence context.** Rows vanish without Hibernate knowing. This is harmless for a terminal operation, but it means the delete must not be composed with a subsequent read of the same entity in the same transaction. It also means `@Modifying` needs `clearAutomatically`/`flushAutomatically` considered — for a single terminal statement in its own transaction, neither is required, and adding them would be cargo cult.

**The consequence of that bypass is load-bearing, and easy to miss:** because a JPQL bulk delete never enters the entity lifecycle, `Analysis`'s `cascade = CascadeType.ALL, orphanRemoval = true` (`Analysis.java:43-47`) **does not fire**. Clauses and negotiation points are removed *only* because `002-create-analyses.yaml:115-128` declares `onDelete: CASCADE` on the two `analysis_id` foreign keys. For this operation the JPA cascade annotations are decorative. Anyone later "cleaning up" the schema by dropping those FK cascades — on the entirely reasonable-sounding grounds that "JPA already cascades" — silently breaks the delete. Say this in a comment on `deleteOwned`, where that reader will be standing.

**CSRF and CORS are separate gates, and both are invisible to normal tests.** `DELETE` is mutating, so `csrf.spa()` (`SecurityConfig.java:36`) requires the `X-XSRF-TOKEN` header — `apiFetch` already attaches it to any non-GET. Independently, the CORS `setAllowedMethods` list (`SecurityConfig.java:75`) must gain `DELETE` or the browser preflight fails *before any Java code runs* — no breakpoint, no server log, no stack trace. MockMvc sends no `Origin` header, so **no ordinary backend test will catch this**; only an explicit `OPTIONS` preflight assertion will.

---

## Phase 1: Schema & error-handling groundwork

### Overview

Two independent, low-risk changes that carry no feature code. Landing them alone against the currently-green suite means any failure is unambiguously attributable.

### Changes Required:

#### 1. Liquibase changeset — FK to `ON DELETE SET NULL`

**File**: `backend/src/main/resources/db/changelog/changes/004-negotiation-points-clause-fk-set-null.yaml` (new)

**Intent**: Close S-01's deferred **F6**. Drop and re-add `fk_negotiation_points_clause` with `onDelete: SET NULL`, so that deleting a `Clause` nulls the referencing `negotiation_points.clause_id` instead of raising a constraint violation. This makes individual-clause deletion safe (it is a foot-gun today) and renders the two-phase test teardowns redundant.

**Contract**: One changeset, `id: 004-negotiation-points-clause-fk-set-null`, `author: falcon` (matching the id-equals-filename-stem convention of `002`). Two changes in order: `dropForeignKeyConstraint` (baseTableName `negotiation_points`, constraintName `fk_negotiation_points_clause`), then `addForeignKeyConstraint` with the same names plus `onDelete: SET NULL`. Valid because `negotiation_points.clause_id` is nullable (`002-create-analyses.yaml:96-98`).

#### 2. Wire the changeset in

**File**: `backend/src/main/resources/db/changelog/db.changelog-master.yaml`

**Intent**: Append the fourth `include` block so Liquibase runs the new changeset.

**Contract**: A `- include: { file: db/changelog/changes/004-negotiation-points-clause-fk-set-null.yaml }` entry appended after the `003` include, matching lines 7-12.

#### 3. Narrow the global DB-violation handler

**File**: `backend/src/main/java/com/morawski/dev/falcon/auth/AuthExceptionHandler.java`

**Intent**: Stop an unscoped advice from claiming every `DataIntegrityViolationException` in the application and answering `409 "Email already in use"`. The handler's own Javadoc scopes its purpose to the registration race; the annotation does not. Scope the advice to the controller it was written for.

**Contract**: Change the class annotation from `@RestControllerAdvice` to `@RestControllerAdvice(assignableTypes = AuthController.class)`. Both handlers (`AuthenticationException` → 401, `DataIntegrityViolationException` → 409) then apply only to `AuthController`, which is where both are actually raised — the manual `authenticationManager.authenticate()` call (`AuthController.java:72`) and the `existsByEmail()`/`save()` race (`AuthController.java:63`) both live there. The anonymous-request 401s asserted in `AuthBoundaryMatrixTest` come from the security filter chain and never reach a controller advice, so they are unaffected. Constraint violations from any other controller now surface as a raw 500 rather than a misleading 409. Update the class Javadoc to state the scoping and why.

**On the limits of the regression proof (Progress 1.3):** `AuthFlowTest` passing proves the advice *still covers* `AuthController` — it cannot prove the narrowing took effect, because both of its assertions originate inside `AuthController` either way. Reverting to a bare `@RestControllerAdvice` would leave the suite fully green. No honest test closes this gap today: no controller other than `AuthController` can currently raise `DataIntegrityViolationException` (`analyses` carries only a PK and FKs, no unique constraint), so there is no violation to provoke and no assertion to write. Accept the gap knowingly rather than let 1.3 imply a coverage it does not provide.

### Success Criteria:

#### Automated Verification:

- Backend builds and the full suite passes: `cd backend && ./mvnw clean package`
- The Spring context boots, proving the changeset is well-formed under `ddl-auto=validate` (any Liquibase error fails every test at once)
- `AuthFlowTest` still passes — duplicate-email registration returns 409, wrong password returns 401 — proving the narrowed advice still covers `AuthController`

#### Manual Verification:

- Inspect the running Postgres: `fk_negotiation_points_clause` reports `ON DELETE SET NULL` (e.g. `\d negotiation_points` in psql against the Compose DB)
- Deleting a single `clauses` row by hand nulls the referencing `negotiation_points.clause_id` instead of erroring
- **Hand-delete a full `analyses` row** (seeded with at least one clause and one negotiation point linked to that clause) with a single `DELETE FROM analyses WHERE id = ?`, and confirm all three tables are empty for that id. This is the one statement that asks Postgres to `SET NULL` a `clause_id` and `DELETE` the very row holding it, both cascading from the same parent — the interaction the whole feature rests on, and the reason this phase is sequenced first and alone. A failure here surfaces as an FK error or `tuple to be updated was already modified`, before any feature code depends on it.

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human before proceeding.

---

## Phase 2: Backend delete endpoint

### Overview

The owner-scoped single-statement delete, its route, its CORS entry, and the four tests that guard the failure modes ordinary tests miss.

### Changes Required:

#### 1. Repository — bulk owner-scoped delete

**File**: `backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisRepository.java`

**Intent**: Add the single-statement delete. It must be a bulk JPQL statement, **not** a derived `deleteByIdAndOwnerId` — the derived form selects then deletes each entity, landing on Hibernate's unsafe multi-statement cascade. The affected-row count carries the authorization result.

**Contract**: A method returning `int` (rows affected), annotated `@Modifying` and `@Query("delete from Analysis a where a.id = :id and a.ownerId = :ownerId")`. Both parameters bound by name. The single emitted `DELETE` lets Postgres cascade to `clauses` and `negotiation_points` within one statement, satisfying the end-of-statement `NO ACTION` check on `negotiation_points.clause_id`. `@Query` and `@Param` are already imported; `@Modifying` is not. Carry a comment recording that child cleanup comes from the DB `ON DELETE CASCADE`, **not** from the entity's JPA cascade, which a bulk JPQL delete never triggers.

This is the one snippet the plan pins, because the *shape* is the correctness argument and a reviewer must be able to check it at a glance:

```java
@Modifying
@Query("delete from Analysis a where a.id = :id and a.ownerId = :ownerId")
int deleteOwned(@Param("id") Long id, @Param("ownerId") Long ownerId);
```

#### 2. Service — delete method

**File**: `backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisService.java`

**Intent**: A public `@Transactional` method that calls the repository and translates a zero-row result into a 404, preserving the "foreign id is indistinguishable from a missing id" invariant. Public and controller-invoked, so the `@Transactional` proxy applies (unlike the self-invocation trap that forced `createAnalysis` onto a `TransactionTemplate`).

**Contract**: `@Transactional public void deleteAnalysis(Long id, Long ownerId)`. Calls `deleteOwned(id, ownerId)`; if the returned count is `0`, throw `new ResponseStatusException(HttpStatus.NOT_FOUND)` — the same bodiless 404 that `getAnalysis` throws (`AnalysisService.java:75-76`).

#### 3. Controller — the route

**File**: `backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisController.java`

**Intent**: Expose `DELETE /api/analyses/{id}`, following the shape of the S-02 PATCH endpoint (`AnalysisController.java:50-54`).

**Contract**: `@DeleteMapping("/{id}")` taking `@PathVariable Long id` and `@AuthenticationPrincipal AppUserDetails principal`; delegates with `principal.getId()`; returns `ResponseEntity<Void>` with `204 No Content` (matching the logout response convention). No request DTO, no response body. `.anyRequest().authenticated()` already gates the route — no security rule to add.

#### 4. CORS — allow the verb

**File**: `backend/src/main/java/com/morawski/dev/falcon/auth/SecurityConfig.java`

**Intent**: Add `DELETE` to the allowed-methods list. Without this the browser preflight rejects the call before any Java code executes — a failure with no stack trace and no server-side log line.

**Contract**: `setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE"))` at line 75.

#### 5. Tests — the four deliberate guards

**File**: `backend/src/test/java/com/morawski/dev/falcon/analysis/AnalysisIsolationTest.java` (extend)

**Intent**: Prove the delete is a no-op across users and that the response leaks no existence information. This mirrors `crossUserPatchAndMissingIdPatchAreIndistinguishable` (`AnalysisIsolationTest.java:89-117`) and adds the assertion PATCH doesn't need: **the victim's analysis must still exist afterwards.**

**Contract**: A test asserting a cross-user `DELETE /api/analyses/{id}` and a `DELETE` of a never-existing id both return `404` with byte-identical bodies, *and* that owner A's analysis is still retrievable from the repository after owner B's attempt. Seed via `analysisRepository.save(...)` with the self-registering `new Clause(analysis, ...)` constructor — no `ChatModel` mock needed; the isolation tests never mock it. Auth via `.with(user(new AppUserDetails(u))).with(csrf())`.

**File**: `backend/src/test/java/com/morawski/dev/falcon/analysis/AnalysisFlowTest.java` (extend)

**Intent**: Prove the happy path actually empties the aggregate — not merely that the `analyses` row is gone. This is the test that would catch a cascade regression.

**Contract**: Seed a full `User → Analysis → Clause → NegotiationPoint` graph (per `AnalysisRepositoryTest.java:40-56`). `DELETE` as the owner → expect `204`. Then assert **all three tables hold zero rows for that analysis id**, and a subsequent `GET /api/analyses/{id}` returns `404`.

Counting the child rows needs a mechanism the suite has never used. Every existing test reaches `clauses` / `negotiation_points` by walking the entity graph — `analysis.getNegotiationPoints()` inside a `TransactionTemplate` (`AnalysisFlowTest.java:96-105`) — which is precisely what becomes impossible once the parent row is deleted: there is no handle left to navigate from. There is no `ClauseRepository` and no `NegotiationPointRepository`, and their absence is deliberate (F-01's invariant, `SecurityUtils.java:7-12`: *"there is no bare `findById` for owned entities"*).

**Autowire a `JdbcTemplate` in this test class** and count directly: `SELECT count(*) FROM clauses WHERE analysis_id = ?` and the same for `negotiation_points`. It is on the classpath via `spring-boot-starter-data-jpa` and binds to the Testcontainers `DataSource` already wired by `@ServiceConnection`. This is the first raw SQL in the backend suite — a deliberate, test-scoped exception, taken **instead of** adding production repositories to satisfy a test assertion. Do not add `ClauseRepository`/`NegotiationPointRepository`.

**File**: `backend/src/test/java/com/morawski/dev/falcon/auth/AuthBoundaryMatrixTest.java` (extend)

**Intent**: Cover the anonymous-caller boundary for the new verb, and assert the CORS preflight advertises it. `AuthBoundaryMatrixTest`'s `@MethodSource("protectedGetRoutes")` fires **GETs only**, so the DELETE cases must be hand-written `@Test` methods mirroring the existing POST/PATCH pairs.

**Contract**: Two hand-written tests — anonymous `DELETE` **without** `.with(csrf())` → `403`; anonymous `DELETE` **with** `.with(csrf())` → `401`. Plus an `OPTIONS` preflight test asserting `DELETE` appears in the `Access-Control-Allow-Methods` response header. That preflight assertion is the *only* automated guard on the CORS list — MockMvc sends no `Origin` header, so nothing else in the suite can see a missing verb.

### Success Criteria:

#### Automated Verification:

- Backend builds and the full suite passes: `cd backend && ./mvnw clean package`
- Owner-delete test passes: `DELETE` returns 204 and `analyses`, `clauses`, `negotiation_points` all hold zero rows for that id
- Cross-user delete test passes: 404 byte-identical to a missing id, and the victim's analysis survives
- Anonymous `DELETE` returns 403 without CSRF and 401 with CSRF
- `OPTIONS` preflight test passes: `DELETE` appears in `Access-Control-Allow-Methods`

#### Manual Verification:

- `curl -X DELETE` against a running backend with a valid session + XSRF token returns 204; a repeat call returns 404
- Postgres shows no orphaned `clauses` or `negotiation_points` rows after the delete

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human before proceeding.

---

## Phase 3: Frontend delete affordance

### Overview

A confirmation dialog, an API wrapper, and the two surfaces. The dashboard row needs restructuring before it can host a button at all.

### Changes Required:

#### 1. Add the dialog primitive

**File**: `frontend/src/components/ui/alert-dialog.tsx` (new, via shadcn CLI)

**Intent**: The first dialog primitive in the repo. `AlertDialog` is purpose-built for destructive confirmation — focus trap, escape-to-cancel, correct ARIA roles. Hand-rolling these is strictly worse.

**Contract**: Added with the shadcn CLI so it lands under `@/components/ui` and inherits the configured `radix-nova` style / `neutral` base from `components.json`. No manual authoring.

#### 2. API wrapper

**File**: `frontend/src/lib/analyses.ts`

**Intent**: Add `deleteAnalysis(id)` following the `updateClauseDecision` template (`lib/analyses.ts:73-84`).

**Contract**: `export async function deleteAnalysis(id: number | string): Promise<void>` — `await getCsrf()` (already imported at line 2), then `apiFetch(\`/api/analyses/${id}\`, { method: "DELETE" })`. `apiFetch` injects `X-XSRF-TOKEN` on any non-GET and throws `ApiError(status, message)` on a non-OK response. No response body to parse.

#### 3. Dashboard list — restructure the row, add delete

**File**: `frontend/src/app/dashboard/page.tsx`

**Intent**: Today the whole `<Card>` is wrapped in a `<Link>` (`:97-109`). A delete `<button>` nested inside an anchor is invalid HTML and would navigate on click. Restructure so the `<Link>` covers only the title/metadata region and the delete control is a **sibling** of the anchor, then wire the confirmation dialog and optimistic row removal.

**Contract**: The `<Link>` no longer wraps `<Card>`; it wraps the title + date block inside `CardContent`. The right-hand flex cell holds the existing status `<Badge>` plus a `<Button variant="destructive" size="sm">` (the `destructive` variant exists and is currently unused). The button opens an `AlertDialog`; confirming calls `deleteAnalysis(id)` and, on success, removes the row from local state — `setAnalyses(prev => prev?.filter(a => a.id !== id) ?? prev)`. On `ApiError` with `status === 401` → `router.push("/login")`, matching `:39-41`. Any other failure renders an inline `text-destructive` message; there is no toast system and this plan does not add one. Note the app is entirely client components with no server actions — do **not** reach for `revalidatePath` or `router.refresh()`.

#### 4. Detail page — add delete

**File**: `frontend/src/app/analyses/[id]/page.tsx`

**Intent**: Surface the same delete on the single-analysis view, redirecting to the dashboard on success.

**Contract**: A `<Button variant="destructive">` in the page header region opening the same `AlertDialog`. Confirming calls `deleteAnalysis(id)`; on success `router.push("/dashboard")`. Error handling mirrors the existing `handleDecide` (`:45-90`): `401 → router.push("/login")`, otherwise an inline `text-destructive` message. There is no optimistic state to roll back — the page is leaving.

#### 5. Polish copy

**File**: inline in the two page components

**Intent**: Match the existing convention — copy is hardcoded Polish in JSX; only enum label maps are centralized. Tone is informal-imperative; errors end with "Spróbuj ponownie."

**Contract**: Button `"Usuń"`; dialog title `"Usunąć analizę?"`; body `"Tej operacji nie można cofnąć."`; confirm `"Usuń"`; cancel `"Anuluj"`; error `"Nie udało się usunąć analizy. Spróbuj ponownie."`

### Success Criteria:

#### Automated Verification:

- Linting passes: `cd frontend && pnpm lint`
- Production build succeeds: `cd frontend && pnpm build`
- No new TypeScript errors in the build output

#### Manual Verification:

- Deleting from the dashboard removes the row without a page reload; the remaining rows are unaffected
- Deleting from the detail page redirects to `/dashboard` and the analysis is absent from the list
- Clicking a dashboard row's title still navigates to the detail page; clicking `Usuń` does **not** navigate
- The dialog can be dismissed with `Escape` and with `Anuluj`, and focus returns to the triggering button
- Keyboard-only: the delete button is reachable by `Tab` and is not announced as nested inside the row link
- With the backend stopped, confirming a delete shows the inline Polish error rather than a silent failure

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human before proceeding.

---

## Phase 4: End-to-end coverage and teardown

### Overview

A browser-level spec for the delete flow, and the teardown that two existing specs have been explicitly waiting for. **Read the Testing Strategy note below before treating any of this as a regression gate** — CI does not run Playwright.

### Changes Required:

#### 1. Delete e2e spec

**File**: `frontend/e2e/delete-analysis.spec.ts` (new)

**Intent**: Verify the delete flow in a real browser — the confirmation dialog, the row removal, and the post-delete absence — which no backend test can observe.

**Contract**: Follows the existing spec conventions: register a fresh account inline through the UI with a `Date.now()`-suffixed email and title (no `storageState`, no global setup — see `analysis-history.spec.ts:18-21`); create an analysis; delete it from the dashboard; assert the row is gone and that reopening its URL shows the not-found state. Locators are `getByRole`/`getByLabel` only; waits are on state (`toBeVisible()`, `waitForURL()`), never `waitForTimeout()`. Because `Analysis.clauses` has **no `@OrderBy`** (S-02 F2), never address list items by index — scope locators to the unique timestamped title.

#### 2. Retrofit teardown into the two waiting specs

**File**: `frontend/e2e/analysis-history.spec.ts`, `frontend/e2e/clause-decision.spec.ts`

**Intent**: Both specs carry a "Known limitation" comment naming S-04 as their unblocker (`analysis-history.spec.ts:9-11`, `clause-decision.spec.ts:17-19`). Delete now exists, so each spec can remove the analysis it created. Redeem the promise and delete the comments.

**Contract**: Each spec deletes its own analysis through the UI in cleanup, and its "Known limitation" comment is removed. The account itself still leaks — account deletion is not in the MVP — so replace, rather than simply delete, the comment with a narrower one stating that the analysis is cleaned up and the user is not.

### Success Criteria:

#### Automated Verification:

- Linting passes: `cd frontend && pnpm lint`
- The e2e suite passes against a running stack: `cd frontend && pnpm test:e2e` (requires `./mvnw spring-boot:run`, `pnpm dev`, and a real `OPENROUTER_API_KEY` — `playwright.config.ts` has no `webServer` block)

#### Manual Verification:

- Re-running the full e2e suite twice in a row leaves no accumulating analyses in the database (accounts still accumulate — that is expected and now documented)
- The delete spec fails, rather than silently passing, if the confirmation dialog is removed

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human.

---

## Testing Strategy

### Unit Tests:

No new pure-unit tests. The delete has no branching logic worth isolating: the repository method is a single statement, and the service's only branch (`0 rows → 404`) is meaningless without a real database to produce the row count. Testing it against a mocked repository would assert that a mock returns what it was told to.

### Integration Tests (the actual regression gate):

Per `test-plan.md §1.1`, the cheapest test that yields real signal wins, and cross-user isolation is **Risk #1, "protect-first"** (`test-plan.md:53,80`). All four backend tests are `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)` against real Postgres — necessary, because the entire correctness argument rests on Postgres's end-of-statement `NO ACTION` semantics, which no mock reproduces.

1. **Owner delete empties the aggregate.** 204, and zero rows in `analyses`, `clauses`, **and** `negotiation_points` for that id. Asserting only the `analyses` row would pass even if the cascade broke.
2. **Cross-user delete is a no-op.** 404 byte-identical to a missing id, **and the victim's analysis survives**. The surviving-row assertion is what distinguishes this from the existing PATCH isolation test.
3. **Anonymous delete is rejected.** 403 without CSRF, 401 with CSRF. Hand-written, because `AuthBoundaryMatrixTest`'s `@MethodSource` is GET-only.
4. **CORS preflight advertises `DELETE`.** The only automated guard on the allowed-methods list; MockMvc sends no `Origin`, so nothing else can catch it.

Note that `@SpringBootTest` tests are **not** auto-rolled-back (`test-plan.md:170`), so new tests clean up explicitly.

### End-to-end Tests:

**These are hygiene, not a gate.** `.github/workflows/ci.yml` runs `./mvnw -B -ntp clean package` for the backend but only `pnpm lint` and `pnpm build` for the frontend — it **never runs `pnpm test:e2e`**. Playwright is not wired into CI and cannot be without a `webServer` block and a live LLM key, both of which `playwright.config.ts` deliberately omits. `test-plan.md` confirms its Phases 3 and 4 remain unstarted.

Consequence: the delete e2e spec verifies the browser-level flow for a human running it locally, and the teardown retrofit stops the suite leaking data. Neither will catch a regression on a pull request. **Any claim that "CI covers delete" must rest on the four integration tests above.**

### Manual Testing Steps:

1. Log in, create an analysis, delete it from the dashboard row. Confirm the row disappears and no reload occurs.
2. Create another, open it, delete from the detail page. Confirm redirect to `/dashboard` and absence from the list.
3. Press `Escape` in the dialog, then `Anuluj`. Confirm neither deletes, and focus returns to the trigger.
4. Tab to the delete button with the keyboard. Confirm it is reachable and that activating the row title still navigates.
5. In a second browser profile, register user B. Note user A's analysis id from the URL. As B, issue `DELETE /api/analyses/{A's id}` via devtools fetch. Confirm 404, then confirm as A that the analysis still exists.
6. Stop the backend and confirm a delete attempt shows the inline Polish error.

## Performance Considerations

Negligible, and favorably so. The bulk delete is a single statement that loads no entities and initializes no lazy collections — strictly cheaper than the `N+1` deletes a Hibernate entity cascade would emit. The existing `idx_analyses_owner_created_at` index on `(owner_id, created_at DESC)` does not serve a lookup by `(id, owner_id)`, but the primary-key index on `analyses.id` does, and the `owner_id` predicate then filters a single row. No new index is warranted.

## Migration Notes

Changeset `004` is a `dropForeignKeyConstraint` + `addForeignKeyConstraint` pair on `negotiation_points`. It rewrites no rows and is safe to apply to a populated database. It is **not** reversible by Liquibase automatically; rolling back means a further changeset restoring the constraint without `onDelete`.

Because `ddl-auto=validate`, a malformed changeset fails the whole Spring context at boot — every test goes red simultaneously with a Liquibase error rather than a feature error. That is why Phase 1 lands alone: a failure there is unambiguous.

No existing data is affected. No `negotiation_points.clause_id` values change on migration; the new `SET NULL` behavior applies only to future clause deletions.

## References

- Research: `context/changes/delete-analysis/research.md`
- Roadmap slice S-04: `context/foundation/roadmap.md:129-139`
- PRD FR-010 and the retention NFR: `context/foundation/prd.md:81-87`
- Test strategy and the Risk #1 isolation cookbook: `context/foundation/test-plan.md:53,80,170,178-183`
- Deferred F6 (the FK): `context/archive/2026-07-06-analyze-and-save-contract/reviews/impl-review.md:73-81`
- The 409 misrouting, filed twice: `context/archive/2026-07-04-identity-and-isolation/reviews/impl-review.md:23-31`, `context/archive/2026-07-06-analyze-and-save-contract/reviews/impl-review.md:23-31`
- The mutation template (service, controller, optimistic frontend): `context/archive/2026-07-09-clause-decision-status/plan.md`
- Cross-user isolation test template: `backend/src/test/java/com/morawski/dev/falcon/analysis/AnalysisIsolationTest.java:89-117`
- The CORS preflight test precedent: `context/archive/2026-07-09-clause-decision-status/plan.md:169-179`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Schema & error-handling groundwork

#### Automated

- [x] 1.1 Backend builds and the full suite passes: `./mvnw clean package` — 0fb088f
- [x] 1.2 Spring context boots, proving the changeset is well-formed under `ddl-auto=validate` — 0fb088f
- [x] 1.3 `AuthFlowTest` still passes — duplicate-email 409, wrong-password 401 — proving the narrowed advice still covers `AuthController` — 0fb088f

#### Manual

- [x] 1.4 Postgres reports `ON DELETE SET NULL` on `fk_negotiation_points_clause` — 0fb088f
- [x] 1.5 Deleting a single `clauses` row nulls the referencing `negotiation_points.clause_id` instead of erroring — 0fb088f
- [x] 1.6 Hand-deleting a full `analyses` row in one statement empties all three tables (exercises SET NULL and both cascades together) — 0fb088f

### Phase 2: Backend delete endpoint

#### Automated

- [x] 2.1 Backend builds and the full suite passes: `./mvnw clean package`
- [x] 2.2 Owner-delete test: 204, and zero rows in `analyses`, `clauses`, `negotiation_points` for that id
- [x] 2.3 Cross-user delete test: 404 byte-identical to a missing id, and the victim's analysis survives
- [x] 2.4 Anonymous `DELETE` returns 403 without CSRF and 401 with CSRF
- [x] 2.5 `OPTIONS` preflight test: `DELETE` appears in `Access-Control-Allow-Methods`

#### Manual

- [x] 2.6 `curl -X DELETE` with a valid session returns 204; a repeat call returns 404
- [x] 2.7 Postgres shows no orphaned `clauses` or `negotiation_points` rows after the delete

### Phase 3: Frontend delete affordance

#### Automated

- [ ] 3.1 Linting passes: `pnpm lint`
- [ ] 3.2 Production build succeeds: `pnpm build`
- [ ] 3.3 No new TypeScript errors in the build output

#### Manual

- [ ] 3.4 Deleting from the dashboard removes the row without a page reload
- [ ] 3.5 Deleting from the detail page redirects to `/dashboard` and the analysis is absent
- [ ] 3.6 Clicking a row title still navigates; clicking `Usuń` does not
- [ ] 3.7 Dialog dismisses with `Escape` and `Anuluj`; focus returns to the trigger
- [ ] 3.8 Delete button is keyboard-reachable and not announced as nested inside the row link
- [ ] 3.9 With the backend stopped, confirming a delete shows the inline Polish error

### Phase 4: End-to-end coverage and teardown

#### Automated

- [ ] 4.1 Linting passes: `pnpm lint`
- [ ] 4.2 E2E suite passes against a running stack: `pnpm test:e2e`

#### Manual

- [ ] 4.3 Re-running the full e2e suite twice leaves no accumulating analyses
- [ ] 4.4 The delete spec fails, rather than silently passing, if the confirmation dialog is removed

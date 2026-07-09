# Clause Decision Status (S-02 / FR-007) Implementation Plan

## Overview

Add the **write half** of FR-007: let a logged-in user set each clause's decision status (`ACCEPTED` / `TO_NEGOTIATE` / `REJECTED`, with `PENDING` as a settable "undo") on their own saved analysis, and have it persist. This turns S-01's read-only breakdown into a working negotiation checklist — the user's decision surface.

## Current State Analysis

The read half already ships. S-01 (`analyze-and-save-contract`) deliberately created the `ClauseDecision` enum, the non-null `user_decision` column defaulting to `PENDING`, and exposed both `userDecision` **and the clause's `id`** on `ClauseResponse`. Its plan states it outright: *"No per-clause decision status UI/endpoint — `Clause.userDecision` defaults to `PENDING`; mutating it is S-02."*

What exists:
- `user_decision` column, migrated (`db/changelog/changes/002-create-analyses.yaml:77`). **No schema change is needed.**
- `Clause.userDecision`, `@Enumerated(STRING)`, initialised to `PENDING` in the constructor.
- `ClauseResponse(Long id, …, ClauseDecision userDecision)` — already on the wire.
- Frontend `ClauseDecision` union type and `Clause.id` in `lib/analyses.ts`.
- `apiFetch` already attaches `X-XSRF-TOKEN` to any non-GET request — `PATCH` needs no client-side change.
- `.anyRequest().authenticated()` already gates any new `/api/analyses/**` route.

What's missing:
- No write endpoint, service method, or request DTO.
- `Clause` and `Analysis` have getters only — no mutator.
- **CORS allows only `GET, POST`** (`SecurityConfig.java:75`).
- No UI control, no Polish decision labels, no installed 3-way choice primitive.

Key constraint discovered: the frontend calls Spring **cross-origin** (`localhost:3000` → `localhost:8080`). `frontend/src/proxy.ts` is a Next 16 route guard checking for `JSESSIONID`, **not** an API proxy. So a `PATCH` is rejected at CORS preflight before any Java handler runs.

## Desired End State

On `/analyses/{id}`, every clause card carries a row of three decision buttons. Clicking one marks the clause immediately; clicking the active one returns it to `PENDING`. The choice survives a page reload. A second user who knows the analysis and clause ids cannot change them — they receive a `404` byte-identical to the one a nonexistent id produces. The "To nie jest porada prawna" disclaimer remains visible.

Verify by: logging in, opening a saved analysis, clicking through decisions, reloading, and confirming persistence; then by `./mvnw test` proving the isolation and boundary contracts.

### Key Discoveries:

- `AnalysisService.getAnalysis` (`AnalysisService.java:69-77`) is the owner-scoping template: `findByIdAndOwnerId(...).orElseThrow(() -> new ResponseStatusException(NOT_FOUND))`. Because the query returns `Optional.empty()` for *both* a missing id and a foreign one, "not found" and "not yours" are indistinguishable. There is **no `403` for ownership** anywhere; a `403` only ever means a CSRF failure.
- `AnalysisRepository` exposes only owner-scoped finders, and there is **no `ClauseRepository`**. `SecurityUtils`' Javadoc codifies it: *"there is no bare `findById` for owned entities."*
- `AnalysisService.java:69-73` carries a hard-won comment: `getAnalysis` needs a transaction spanning **both** the fetch and `toResponse()`, or the lazy `@OneToMany` collections throw `LazyInitializationException` on a detached entity (`spring.jpa.open-in-view=false` in both profiles).
- `Analysis.clauses` is `@OneToMany(cascade = ALL, orphanRemoval = true)` with **no `@OrderBy`** — never address a clause by list index.
- `NegotiationPoint.setClauseId(...)` exists (`AnalysisService.java:57`), so "no setters" is precisely true of `Clause` and `Analysis`, not of every entity.
- Label maps are **colocated with their type**, not centralised in `risk.ts`: `ANALYSIS_STATUS_LABEL` sits beside `AnalysisStatus` in `lib/analyses.ts:9-13`. `CLAUSE_DECISION_LABEL` therefore belongs in `analyses.ts`, next to `ClauseDecision` at line 5.
- `AuthBoundaryMatrixTest`'s `@MethodSource("protectedGetRoutes")` fires **GETs only**. A `PATCH` route needs two hand-written `@Test` methods, mirroring the existing POST pair, not a new row in the stream.
- Every backend MVC test authenticates with `.with(user(new AppUserDetails(u)))` — never `@WithMockUser` — and every **mutation** additionally needs `.with(csrf())`. The existing isolation tests are GET-only and omit it.

## What We're NOT Doing

- **No `Analysis.status` transition.** `ANALYZED → REVIEWED` stays unwritten. FR-007 says nothing about it, no UI surfaces status on this page, and nothing reads it — a derived invariant with no consumer is speculative state. `REVIEWED` remains reserved.
- **No new shadcn component.** No `select`, `toggle-group`, `radio-group`, or `sonner`. The existing `Button` variants carry the control.
- **No schema change / Liquibase changelog.** The column already exists.
- **No `ClauseRepository`.** Clauses stay reachable only through their `Analysis` aggregate — this is what keeps owner-scoping structural.
- **No bulk/multi-clause update endpoint.** One clause per request.
- **No e2e cleanup / teardown.** Blocked until S-04 ships delete; the existing spec has the same gap. Use `Date.now()`-suffixed ids and document it.
- **No fix for the pre-existing `useEffect` unmount/abort gap** flagged in `analysis-history`'s impl-review (F4) — accepted convention, not to be fixed piecemeal here.

## Implementation Approach

Route the mutation through the aggregate root. `AnalysisService.updateClauseDecision` loads the `Analysis` with the *same* owner-scoped query the read path uses, then asks the aggregate to mutate its own clause. A clause belonging to another user's analysis is unreachable because the parent load already returned `Optional.empty()` — ownership is inherited by construction rather than re-checked.

This is the codebase's first mutation of a persisted entity, so the mutator is deliberately narrow: a package-private setter on `Clause`, reachable only from `Analysis.decide(...)` in the same package. JPA dirty-checking flushes it on commit; no `save()` call is needed.

On the frontend, a segmented row of existing `Button`s updates local state optimistically, fires the `PATCH`, and rolls that single clause back on `ApiError` while surfacing inline Polish copy on the affected card.

## Critical Implementation Details

**Timing & lifecycle.** Phase 1 must land the CORS method-list fix before Phase 2 is testable in a browser. A missing `PATCH` in `setAllowedMethods` fails at *preflight*: the browser reports an opaque CORS error, no Java handler is entered, and no breakpoint or server log will fire. Debugging this from the frontend is disproportionately painful, which is why the security edit belongs in the backend phase rather than alongside the UI that first exercises it.

**Transaction boundary — and why getting it wrong is silent.** `updateClauseDecision` must be annotated `@Transactional` (read-write) on a **public, externally-invoked** method. The reason is **dirty-check flush**, not lazy loading: `decide(...)` has already materialized the clause, so nothing lazy is touched on the way out. Note this carefully, because the failure mode is the opposite of an exception. Without an open transaction, the `Analysis` returned by `findByIdAndOwnerId` is **detached**; mutating its clause changes an in-memory object that Hibernate never flushes. The endpoint still returns `200` with the new value — read straight back off the object that was mutated — and the database is unchanged. **Nothing throws.** The only way to observe it is to re-read in a separate request, which is why the flow test must assert a `PATCH` → `GET` round-trip rather than merely inspecting the `PATCH` response body.

The second, related trap: a *self-invoked* `@Transactional` is a silent no-op under Spring's proxy AOP, which is why `createAnalysis` reaches for a `TransactionTemplate` (see `AnalysisService.java:30-33`). Here the controller invokes the service externally, so a plain `@Transactional` is correct.

**State sequencing (frontend).** The optimistic handler must capture the clause's prior `userDecision` *before* calling `setAnalysis`, or the rollback closure will read the already-updated value and restore the wrong state. Roll back only the one clause, leaving other in-flight edits untouched. The existing `ApiError`/`status === 401` → `router.push("/login")` behaviour must still fire when a session expires mid-triage.

---

## Phase 1: Backend write path

### Overview

Introduce the aggregate mutation, the `PATCH` sub-resource, and the CORS fix — then prove the owner-scoping and auth-boundary contracts hold.

### Changes Required:

#### 1. Clause mutator

**File**: `backend/src/main/java/com/morawski/dev/falcon/analysis/Clause.java`

**Intent**: Give `Clause` its first mutator, scoped so only the aggregate root can call it. Keeps the "read-only after construction" convention meaningfully intact for external callers.

**Contract**: A **package-private** `void setUserDecision(ClauseDecision userDecision)`. Not `public`. No other setter is added.

#### 2. Aggregate decision method

**File**: `backend/src/main/java/com/morawski/dev/falcon/analysis/Analysis.java`

**Intent**: Let the aggregate root locate one of its own clauses by id and set its decision, so the clause lookup can never be written against a foreign analysis.

**Contract**: `public Clause decide(Long clauseId, ClauseDecision decision)`. Searches `this.clauses` by `getId()` (never by index — there is no `@OrderBy`), calls the package-private setter, and **returns the mutated clause** so the service can map a response without re-finding it. Throws `java.util.NoSuchElementException` when the id is not in this aggregate; the service translates that to `404`.

#### 3. Request DTO

**File**: `backend/src/main/java/com/morawski/dev/falcon/analysis/dto/UpdateClauseDecisionRequest.java` (new)

**Intent**: Carry the target decision, validated at the boundary.

**Contract**: `public record UpdateClauseDecisionRequest(@NotNull ClauseDecision decision) {}`. All four enum values are accepted, including `PENDING` (the undo). An unknown string yields Jackson's automatic `400`; a null yields `@Valid`'s `400`. No `DataIntegrityViolationException` is reachable here (enum column, no unique constraint), so the unmapped-constraint bug that bit F-01 and S-01 does not apply.

#### 4. Service method

**File**: `backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisService.java`

**Intent**: Load the analysis owner-scoped, delegate the mutation to the aggregate, and map the updated clause back. Ownership is inherited from the query; a missing clause is collapsed into the same `404` as a missing or foreign analysis, so no id-existence signal leaks.

**Contract**: Signature and the `404` collapse are load-bearing — later phases and tests depend on both:

```java
@Transactional
public ClauseResponse updateClauseDecision(Long analysisId, Long clauseId, ClauseDecision decision, Long ownerId) {
    Analysis analysis = analysisRepository.findByIdAndOwnerId(analysisId, ownerId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    try {
        Clause updated = analysis.decide(clauseId, decision);
        // dirty-checking flushes on commit; no save() call
        return toClauseResponse(updated);
    } catch (NoSuchElementException e) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
```

Returns `ClauseResponse` rather than the full `AnalysisResponse`: it needs no new DTO and avoids re-mapping the `negotiationPoints` collection.

No single-clause mapper exists today — the `Clause` → `ClauseResponse` mapping is an inline lambda inside the private `toResponse` (`AnalysisService.java:97-100`). **Extract it** to `private static ClauseResponse toClauseResponse(Clause c)` and call it from both `toResponse` and the new method. All six `ClauseResponse` components map one-to-one to public `Clause` getters, so the extraction is mechanical.

#### 5. Controller endpoint

**File**: `backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisController.java`

**Intent**: Expose the mutation as a nested sub-resource, matching the controller's existing thin, principal-passing style.

**Contract**: `PATCH /api/analyses/{analysisId}/clauses/{clauseId}` → `200` with `ClauseResponse`. Takes `@PathVariable Long analysisId`, `@PathVariable Long clauseId`, `@Valid @RequestBody UpdateClauseDecisionRequest`, `@AuthenticationPrincipal AppUserDetails principal`; passes `principal.getId()` as `ownerId`. The owner is **never** read from the request body.

#### 6. CORS method list

**File**: `backend/src/main/java/com/morawski/dev/falcon/auth/SecurityConfig.java`

**Intent**: Permit the `PATCH` preflight. Without this the browser rejects the request before it reaches Spring.

**Contract**: `configuration.setAllowedMethods(List.of("GET", "POST", "PATCH"))` at line 75. Nothing else in `SecurityConfig` changes — `.anyRequest().authenticated()` already gates the route and `csrf.spa()` already protects `PATCH`.

#### 7. Aggregate unit test

**File**: `backend/src/test/java/com/morawski/dev/falcon/analysis/AnalysisDecideTest.java` (new)

**Intent**: Pin the aggregate's behaviour directly, with no Spring context and no database — it runs in milliseconds and localises failures to the domain rule rather than the HTTP layer.

**Contract**: Plain JUnit, no annotations beyond `@Test`. Build an `Analysis` and attach clauses via the existing `new Clause(analysis, …)` constructor (it self-wires into the parent). Assert that `decide(...)` sets the decision on the clause with the matching id and leaves siblings untouched, returns that clause, and throws `NoSuchElementException` for an id not in the aggregate. Clauses are unsaved here so their ids are null — assign ids via the constructor path used by the other tests, or assert on the returned instance rather than by id lookup.

#### 8. Isolation regression test

**File**: `backend/src/test/java/com/morawski/dev/falcon/analysis/AnalysisIsolationTest.java`

**Intent**: Prove the guardrail named in `test-plan.md` §2 Risk #1 — user B cannot *mutate* user A's clause — and that the refusal leaks no existence signal. This file stays focused on cross-user refusal; the happy path lives in the flow test.

**Contract**: Add tests, seeding via the repository (no LLM mock needed):
- Owner B `PATCH`es owner A's clause → `404`, byte-identical (status **and body**) to a `PATCH` against a nonexistent analysis id. Mirrors the existing `crossUserGetAndMissingIdGetAreIndistinguishable` idiom, which already compares `getContentAsString()` and not merely the status.
- A clause id belonging to a *different analysis of the same owner* → `404` (proves `decide` searches only within the loaded aggregate).

Both use `.with(csrf()).with(user(new AppUserDetails(u)))`. **The existing tests in this file are GET-only and omit `csrf()`; a mutation without it returns a misleading `403`.** Assert the target clause by id, never by list index.

#### 9. Auth boundary and CORS preflight tests

**File**: `backend/src/test/java/com/morawski/dev/falcon/auth/AuthBoundaryMatrixTest.java`

**Intent**: Extend the anonymous-access matrix to the new verb, as `testing-auth-boundary-regression`'s research explicitly required for S-02 — and automate the CORS guard, which no other test can observe.

**Contract**: Three new `@Test` methods.

Two mirror the existing `anonymousPostWithoutCsrfReturns403` / `anonymousPostWithCsrfReturns401` pair (both of which hit the `/api/analyses` collection): an anonymous `PATCH` without CSRF → `403`; with CSRF → `401`. Do **not** add the route to `protectedGetRoutes` — that `@MethodSource` issues GETs only.

The third is load-bearing and easy to omit. **`MockMvc` sends no `Origin` header, so `CorsFilter` lets every test request through regardless of `setAllowedMethods` — no other test in this plan can detect a missing `PATCH` in the CORS list.** Extend the existing `corsPreflightIsPermitted` (`:65`) or add a sibling that issues an `OPTIONS` request carrying `Origin: http://localhost:3000` and `Access-Control-Request-Method: PATCH`, and asserts `PATCH` appears in the returned `Access-Control-Allow-Methods` header. This is what makes CI, rather than a human, the guard on change #6.

#### 10. Flow round-trip test

**File**: `backend/src/test/java/com/morawski/dev/falcon/analysis/AnalysisFlowTest.java`

**Intent**: Cover the full HTTP round-trip, the `PENDING` undo, and — critically — that the decision is actually **persisted** rather than merely mutated in memory.

**Contract**: Reuse the class's existing `MockChatModelConfig` and its two-phase FK-safe `@AfterEach` cleanup.

- Owner `PATCH`es a clause to `TO_NEGOTIATE` → `200`, then a **separate `GET`** of the analysis shows `userDecision == TO_NEGOTIATE`. The `GET` is not optional: asserting only the `PATCH` response body would pass even if the write never flushed, because that body is mapped off the same in-memory object the mutation touched (see *Critical Implementation Details → Transaction boundary*).
- A second `PATCH` to `PENDING` → `200`, and a following `GET` shows it cleared.
- A `PATCH` with an unparseable decision string → `400`. (Verified: neither `@RestControllerAdvice` declares a broad handler, so Jackson's `HttpMessageNotReadableException` reaches Spring's default → `400`.)

### Success Criteria:

#### Automated Verification:

- Backend compiles and the full suite passes: `cd backend && ./mvnw clean package`
- Aggregate rule holds: `./mvnw test -Dtest=AnalysisDecideTest`
- Isolation contract holds: `./mvnw test -Dtest=AnalysisIsolationTest`
- Auth boundary **and the PATCH CORS preflight** hold: `./mvnw test -Dtest=AuthBoundaryMatrixTest`
- Persistence round-trip, undo, and `400` on a bad enum hold: `./mvnw test -Dtest=AnalysisFlowTest`

#### Manual Verification:

- Setting a decision persists across a backend restart (the column, not just the session)
- A real browser `PATCH` from `http://localhost:3000` completes without a CORS error

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 2: Frontend decision control

### Overview

Render each clause's current decision and let the user change it in place, optimistically, with rollback and inline error copy on failure.

### Changes Required:

#### 1. Labels and API wrapper

**File**: `frontend/src/lib/analyses.ts`

**Intent**: Add the Polish label map beside its type (matching how `ANALYSIS_STATUS_LABEL` sits beside `AnalysisStatus`), and a typed mutation wrapper matching `createAnalysis`'s shape.

**Contract**:
- `export const CLAUSE_DECISION_LABEL: Record<ClauseDecision, string>` placed next to `ClauseDecision` at line 5. Suggested copy: `PENDING: "Bez decyzji"`, `ACCEPTED: "Akceptuję"`, `TO_NEGOTIATE: "Do negocjacji"`, `REJECTED: "Odrzucam"`.
- `export async function updateClauseDecision(analysisId: number, clauseId: number, decision: ClauseDecision): Promise<Clause>` — calls `await getCsrf()` first, then `apiFetch(..., { method: "PATCH", body: JSON.stringify({ decision }) })`, returning `response.json()`. Mirrors `createAnalysis` (`analyses.ts:47-54`). Errors bubble as `ApiError`; no local handling.

#### 2. Segmented decision control

**File**: `frontend/src/app/analyses/[id]/page.tsx`

**Intent**: Add a per-clause decision row inside the existing clause `Card`, with optimistic update, single-clause rollback, and inline failure copy. Preserve the disclaimer and the existing `401 → /login` behaviour.

**Contract**:
- A row of three `Button`s per clause, rendered after the rationale (`page.tsx:98`) and before the negotiation points (`:100`). The active decision uses a filled variant (`variant="default"`); the others `variant="outline"`. Use `size="sm"`. Clicking the active one sends `PENDING`.
- The three buttons are wrapped in `<div role="group" aria-label="Decyzja: klauzula {n}">`, where `{n}` is the clause's 1-based position. **Both a11y and Phase 3 depend on this**: every clause card renders the same three button labels, so an unscoped `getByRole("button", { name })` would match every clause at once and trip Playwright's strict mode. The labeled group gives the spec a stable scope and gives a screen-reader user a way to tell one clause's controls from another's — which is what criterion 2.8 actually promises.
- Each button carries its own accessible name (the Polish label) and `aria-pressed` reflecting the active state. Verified that `Button` spreads `...props` onto a native `<button>` (`button.tsx:49,62`), so `role`, `aria-*`, and `onClick` pass through unchanged.
- Handler: capture the clause's prior `userDecision`, `setAnalysis` optimistically, `await updateClauseDecision(...)`; on `ApiError` with `status === 401` call `router.push("/login")`, otherwise restore *only that clause's* prior value and set an inline error message on it.
- Error state is per-clause (e.g. `Record<number, string>` keyed by clause id), rendered as small destructive-toned text within the card. No toast component is introduced.
- The `<Alert>` disclaimer at `:74-80` must remain untouched and visible.

### Success Criteria:

#### Automated Verification:

- Lint passes: `cd frontend && pnpm lint`
- Production build passes (type-checks the new wrapper and label map): `cd frontend && pnpm build`

#### Manual Verification:

- Clicking a decision marks the clause immediately, with no visible round-trip
- The decision survives a page reload (`F5`)
- Clicking the currently-active decision returns the clause to `Bez decyzji`
- With the backend stopped, a click rolls that clause back and shows inline Polish error copy — other clauses' decisions are unaffected
- The "To nie jest porada prawna" disclaimer is still visible on the page
- Decision buttons are reachable and operable by keyboard, and each announces its label

**Implementation Note**: After completing this phase and all automated verification passes, pause here for manual confirmation from the human that the manual testing was successful before proceeding to the next phase.

---

## Phase 3: End-to-end coverage

### Overview

Prove the whole flow through a real browser against a real backend, following the conventions of the one existing spec.

### Changes Required:

#### 1. Playwright spec

**File**: `frontend/e2e/clause-decision.spec.ts` (new)

**Intent**: Drive the decision control end-to-end and assert persistence across a reload.

**Contract**: Mirror `frontend/e2e/analysis-history.spec.ts` exactly in style — register a fresh account through the UI with a `Date.now()`-suffixed email, create an analysis, open it, click a decision, reload, assert it persisted.

Scope the locator through the accessible group Phase 2 adds, then find the button within it — `page.getByRole("group").first().getByRole("button", { name: "Do negocjacji" })`. An unscoped `getByRole("button", { name })` matches one element per clause and trips strict mode. `getByLabel` for the registration and analysis forms. Never CSS, never `getByTestId`. Waits use `waitForURL` and `toBeVisible()` — **never `page.waitForTimeout()`**.

**The spec must tolerate a variable clause count.** It seeds through the real UI against the live LLM, so the number of clauses is non-deterministic; assert against the first clause group rather than an expected total. Like the existing spec, it therefore needs a running stack plus `OPENROUTER_API_KEY` and is a **local-only gate** — CI runs only `pnpm lint` + `pnpm build` for the frontend. No teardown exists (blocked on S-04's delete); note that limitation in a file-header comment as the existing spec does.

### Success Criteria:

#### Manual Verification:

*(Local-only: these need a running stack and `OPENROUTER_API_KEY`, and drive a non-deterministic LLM response. They are deliberately not filed as Automated — CI does not run Playwright, and the PRD's test-determinism guardrail is carried by the mocked-LLM backend tests instead.)*

- The new spec passes locally against a running stack: `cd frontend && pnpm test:e2e clause-decision.spec.ts`
- The existing spec still passes: `cd frontend && pnpm test:e2e analysis-history.spec.ts`
- CI remains green and its duration is unchanged (neither spec is run by CI)

---

## Testing Strategy

### Unit / Integration Tests:

- Aggregate behaviour (`AnalysisDecideTest`, plain JUnit): `Analysis.decide(...)` sets the right clause, leaves siblings untouched, returns it, and throws for an id outside the aggregate
- Owner-scoping: cross-user `PATCH` → `404`, byte-identical to a missing-id `404`
- Aggregate boundary: a clause id from another analysis *of the same owner* → `404`
- Auth boundary: anonymous `PATCH` without CSRF → `403`; with CSRF → `401`
- CORS: an `OPTIONS` preflight requesting `PATCH` is answered with `PATCH` in `Access-Control-Allow-Methods` — the only automated guard on the CORS list, since `MockMvc` otherwise bypasses `CorsFilter` entirely
- Validation: unknown decision string → `400`
- Persistence round-trip: `PATCH` then a **separate** `GET` reflects the new `userDecision`; `PENDING` undo clears it

### End-to-End:

- Register → create analysis → set a clause to `Do negocjacji` → reload → decision persisted

### Manual Testing Steps:

1. Log in, open a saved analysis, click `Do negocjacji` on a clause — it marks instantly.
2. Reload the page — the decision is still there.
3. Click the active decision again — the clause returns to `Bez decyzji`.
4. Stop the backend, click a decision — that clause rolls back and shows an inline error; other clauses are untouched.
5. Confirm the "To nie jest porada prawna" disclaimer is visible throughout.
6. Log in as a second user and request the first user's analysis id — still `404`.

## Performance Considerations

None material. One row update per click on a low-QPS, small-data app. The optimistic update means perceived latency is zero; the `PATCH` payload is a single enum and the response is one clause, not the whole analysis.

## Migration Notes

No schema change. `user_decision` already exists as a non-null column defaulting to `PENDING` (`002-create-analyses.yaml:77`), and every existing row already holds `PENDING`. No backfill, no changelog, and therefore no risk from the test profile's `ddl-auto=validate`.

## References

- Related research: `context/changes/clause-decision-status/research.md`
- Owner-scoping template: `backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisService.java:69-77`
- Transaction/lazy-init lesson: `AnalysisService.java:30-33` and `:69-73`
- Mutation UI pattern: `frontend/src/app/analyses/new/page.tsx:28-52`
- Label-map convention: `frontend/src/lib/analyses.ts:9-13`
- Isolation test idiom: `backend/src/test/java/com/morawski/dev/falcon/analysis/AnalysisIsolationTest.java`
- Isolation cookbook: `context/foundation/test-plan.md` §6.4; guardrail: §2 Risk #1
- Deferral of this slice: `context/archive/2026-07-06-analyze-and-save-contract/plan.md:31`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Backend write path

#### Automated

- [x] 1.1 Backend compiles and the full suite passes: `cd backend && ./mvnw clean package` — e66a101
- [x] 1.2 Aggregate rule holds: `./mvnw test -Dtest=AnalysisDecideTest` — e66a101
- [x] 1.3 Isolation contract holds: `./mvnw test -Dtest=AnalysisIsolationTest` — e66a101
- [x] 1.4 Auth boundary and the PATCH CORS preflight hold: `./mvnw test -Dtest=AuthBoundaryMatrixTest` — e66a101
- [x] 1.5 Persistence round-trip, undo, and `400` on a bad enum hold: `./mvnw test -Dtest=AnalysisFlowTest` — e66a101

#### Manual

- [x] 1.6 Setting a decision persists across a backend restart — e66a101
- [x] 1.7 A real browser `PATCH` from `http://localhost:3000` completes without a CORS error — e66a101

### Phase 2: Frontend decision control

#### Automated

- [x] 2.1 Lint passes: `cd frontend && pnpm lint` — 8c428b4
- [x] 2.2 Production build passes: `cd frontend && pnpm build` — 8c428b4

#### Manual

- [x] 2.3 Clicking a decision marks the clause immediately, with no visible round-trip — 8c428b4
- [x] 2.4 The decision survives a page reload — 8c428b4
- [x] 2.5 Clicking the active decision returns the clause to `Bez decyzji` — 8c428b4
- [x] 2.6 With the backend stopped, a click rolls back only that clause and shows inline error copy — 8c428b4
- [x] 2.7 The "To nie jest porada prawna" disclaimer is still visible — 8c428b4
- [x] 2.8 Decision buttons are keyboard-operable and announce their labels — 8c428b4

### Phase 3: End-to-end coverage

#### Manual

- [x] 3.1 The new spec passes locally against a running stack: `cd frontend && pnpm test:e2e clause-decision.spec.ts` — 3896d85
- [x] 3.2 The existing spec still passes: `cd frontend && pnpm test:e2e analysis-history.spec.ts` — 3896d85
- [x] 3.3 CI remains green and its duration is unchanged (neither spec is run by CI) — 3896d85

# Delete an analysis (S-04) — Plan Brief

> Full plan: `context/changes/delete-analysis/plan.md`
> Research: `context/changes/delete-analysis/research.md`

## What & Why

Let a logged-in user hard-delete one of their saved analyses. This is roadmap slice **S-04** / PRD **FR-010**, and it is the MVP's *entire* retention mechanism — analyses persist until their owner removes them, with no automatic expiry. For sensitive contract data, user-initiated deletion is a privacy affordance, not mere CRUD.

## Starting Point

The backend has POST, GET-list, GET-by-id, and the S-02 clause PATCH — but no delete anywhere, in any layer. Ownership is already enforced as a query shape (`findByIdAndOwnerId`), so a foreign id and a missing id both yield an identical bodiless 404. The frontend has a dashboard list and a detail page, no dialog primitive, and no toast system. Two Playwright specs carry a literal "Known limitation" comment naming S-04 as the thing that will finally let them clean up after themselves.

The hazard is in the schema. `clauses.analysis_id` and `negotiation_points.analysis_id` both cascade on delete, but `fk_negotiation_points_clause` declares **no `onDelete`** — so Postgres applies `NO ACTION`, checked at *end of statement*. That single timing property decides the implementation.

## Desired End State

A delete control sits on each dashboard row and on the analysis detail page. Activating it opens a Polish confirmation dialog; confirming removes the analysis and all of its clauses and negotiation points, and the row disappears (or the page redirects to `/dashboard`). Another user attempting the same delete gets a response byte-identical to one for an id that never existed, and the analysis survives untouched.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Delete semantics | Hard delete, no tombstone | PRD NFR: "deletion is user-initiated and **removes** the analysis." | Research |
| Delete mechanism | Bulk `@Modifying` JPQL, `0 rows → 404` | One statement lets Postgres cascade both children before the end-of-statement `NO ACTION` check fires; Hibernate's entity cascade would delete `clauses` first and violate the FK. | Plan |
| `deleteByIdAndOwnerId` | Rejected | Spring Data's derived `deleteBy…` selects then deletes per entity — it *reads* like a bulk statement and *executes* as the unsafe cascade. | Plan |
| Changeset 004 | Ship it (`FK → ON DELETE SET NULL`) | Closes S-01's deferred **F6** where it was filed; defuses the live foot-gun of deleting an individual clause. | Plan |
| Dashboard row | Restructure so the button escapes the anchor | A `<button>` nested in the row's full-card `<Link>` is invalid HTML and navigates on click. | Plan |
| Confirmation UI | Add shadcn `alert-dialog` | "Destructive and hard to trigger by accident" is exactly what `AlertDialog` is for; hand-rolling a focus-trapped modal is strictly worse. | Plan |
| `409` misrouting | Narrow `AuthExceptionHandler` to `AuthController` | An unscoped advice maps *any* `DataIntegrityViolationException` to "Email already in use" — a bug class already filed twice in review. | Plan |
| E2E scope | New spec + retrofit teardown | Redeems the promise both existing specs wrote down; but CI never runs Playwright, so it is hygiene, not a gate. | Plan |
| Counting deleted child rows | Test-scoped `JdbcTemplate` | Once the parent row is gone there is no entity graph to walk; adding `ClauseRepository` to serve a test would breach F-01's invariant. | Plan review |

## Scope

**In scope:** `DELETE /api/analyses/{id}` (owner-scoped, `204`); Liquibase changeset `004`; `DELETE` added to CORS; narrowing the auth exception advice; `alert-dialog` primitive; delete on both the dashboard and the detail page; four backend integration tests; a delete e2e spec plus teardown retrofit.

**Out of scope:** soft delete / undo; bulk multi-select delete; toast system; `dropdown-menu`; user-account deletion; simplifying the now-redundant two-phase test teardowns; wiring Playwright into CI; `@OrderBy` on `Analysis.clauses`.

## Architecture / Approach

`DELETE /api/analyses/{id}` → controller reads `@AuthenticationPrincipal` → `@Transactional` service → repository issues **one** `DELETE FROM analyses WHERE id = ? AND owner_id = ?`. Postgres runs both `ON DELETE CASCADE` paths inside that statement, so when the deferred `NO ACTION` check on `negotiation_points.clause_id` fires at end-of-statement, the referencing rows are already gone.

The affected-row count *is* the authorization result: because `owner_id` is a `WHERE` predicate, a foreign id deletes zero rows, and the 404 falls out of the query shape rather than from a check — which is precisely how every other owned read in this codebase enforces isolation.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Schema & error-handling groundwork | Changeset `004`; `AuthExceptionHandler` scoped to `AuthController` | `ddl-auto=validate` means a bad changeset reddens the *entire* suite at boot — which is why this lands alone, where attribution is unambiguous |
| 2. Backend delete endpoint | Bulk delete, service, route, CORS verb, 4 tests | The CORS omission and the FK ordering are both invisible to ordinary tests; each needs a deliberate one |
| 3. Frontend delete affordance | `alert-dialog`, API wrapper, both surfaces, Polish copy | Restructuring the row link risks regressing navigation and keyboard access |
| 4. E2E coverage and teardown | Delete spec; cleanup retrofitted into two waiting specs | Easy to mistake for a regression gate — CI does not run Playwright |

**Prerequisites:** S-01 (a saved analysis to delete) — done. Docker for Testcontainers. For Phase 4 only: a running backend + frontend and a real `OPENROUTER_API_KEY`, since `playwright.config.ts` has no `webServer` block.
**Estimated effort:** ~2-3 sessions across 4 phases; Phases 1 and 2 are small and mechanical, Phase 3 carries most of the judgment.

## Open Risks & Assumptions

- **The correctness argument rests on Postgres's `NO ACTION` being an end-of-statement check, not an immediate one.** This is why the integration tests run against real Postgres via Testcontainers rather than any mock — the guarantee is a database behavior, not a Java one. Phase 1's manual verification now hand-deletes a full `analyses` row so this composes-cleanly claim is exercised before any feature code depends on it.
- **The bulk delete relies on the DB `ON DELETE CASCADE`, not the JPA cascade** — a JPQL bulk delete never enters the entity lifecycle, so `cascade = ALL, orphanRemoval = true` is inert here. Dropping those FK cascades in a future schema cleanup would silently break the feature.
- **The `AuthExceptionHandler` narrowing has no automated proof.** `AuthFlowTest` shows the advice still covers auth, not that it stopped covering everything else; no controller outside `AuthController` can currently raise `DataIntegrityViolationException`, so there is nothing honest to assert.
- **Narrowing `AuthExceptionHandler` touches the auth module from a delete slice.** Its regression proof is that `AuthFlowTest` (409 on duplicate email, 401 on wrong password) keeps passing. Constraint violations elsewhere will now surface as a raw 500 — that is the intended, more honest behavior.
- **The delete e2e spec and the teardown retrofit will not run on any pull request.** CI runs `pnpm lint` and `pnpm build` for the frontend and nothing else. The regression gate is the four backend integration tests, and the plan says so explicitly rather than letting a green spec imply coverage.
- **Accounts still leak in the e2e suite** after this lands. Only analyses get cleaned up; user deletion is not in the MVP. The "Known limitation" comments are narrowed, not removed.

## Success Criteria (Summary)

- A user can delete their own analysis from both the dashboard and the detail page, behind a confirmation they cannot trigger by accident, and the clauses and negotiation points go with it.
- Another authenticated user attempting that same delete cannot tell the analysis ever existed — and it still does, for its owner.
- `./mvnw clean package` and `pnpm lint && pnpm build` pass, with new tests covering the cascade, the cross-user no-op, the anonymous rejection, and the CORS preflight.

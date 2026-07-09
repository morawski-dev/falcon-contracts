# Clause Decision Status (S-02 / FR-007) — Plan Brief

> Full plan: `context/changes/clause-decision-status/plan.md`
> Research: `context/changes/clause-decision-status/research.md`

## What & Why

Let a logged-in user mark each clause of a saved analysis as accepted, to-negotiate, or rejected — and have it persist. Per the PRD's own defence of FR-007, *"the status is what turns a read-only report into a working negotiation checklist — it is the user's decision surface."* Without it, Falcon shows you the risks but gives you nowhere to record what you decided about them.

## Starting Point

The read half already ships, on purpose. S-01 created the `ClauseDecision` enum, the non-null `user_decision` column defaulting to `PENDING`, and put both `userDecision` and each clause's `id` on the wire — then explicitly deferred mutation: *"mutating it is S-02."* Today the frontend receives `userDecision` and drops it. What's missing is a write path: no endpoint, no mutator on the entity, no UI control — and a CORS method list that allows only `GET, POST`.

## Desired End State

Every clause card on `/analyses/{id}` carries three decision buttons. Clicking one marks the clause instantly; clicking the active one clears it back to undecided. The choice survives a reload. A second user who somehow knows the analysis and clause ids gets a `404` indistinguishable from one for an id that never existed. The "not legal advice" disclaimer stays visible.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| HTTP verb & route | `PATCH /api/analyses/{analysisId}/clauses/{clauseId}` | Semantically honest single-field partial update; the clause `id` is already on the wire, and the route nests the way the controller already nests. | Plan |
| Domain mutation shape | `Analysis.decide(clauseId, decision)` + package-private `Clause` setter | Keeps the invariant inside the aggregate root, so no future caller can bypass owner-scoping via a bare clause lookup. | Plan |
| `PENDING` as undo | Settable — all four enum values accepted | Costs nothing (the value exists, Jackson validates it free) and a checklist you can't un-tick is a bad checklist. | Plan |
| `Analysis.status` → `REVIEWED` | Out of scope | FR-007 is silent on it and nothing reads the value; a derived invariant with no consumer is speculative state. | Plan |
| UI control | Segmented row of existing `Button`s | Zero new dependencies or component files; all options stay visible, which suits scan-and-triage. | Plan |
| Update & error feedback | Optimistic, with per-clause rollback and inline error | Instant feel on the app's primary triage surface, and it needs no toast component (none is installed). | Plan |
| Ownership enforcement | Reuse `findByIdAndOwnerId`; collapse "missing" and "foreign" into one `404` | The existing query-level invariant; re-checking ownership in the service would be reinventing it. | Research |
| Schema | No migration | The `user_decision` column already exists and every row holds `PENDING`. | Research |

## Scope

**In scope:** the aggregate mutation and package-private setter; a validated `UpdateClauseDecisionRequest`; a `@Transactional` service method; the `PATCH` handler; the CORS `PATCH` fix; Polish decision labels and a typed API wrapper; a segmented per-clause control with optimistic update and rollback; isolation, auth-boundary, flow, and e2e tests.

**Out of scope:** any `Analysis.status` transition; new shadcn components; any Liquibase changelog; a `ClauseRepository`; bulk multi-clause updates; e2e teardown (blocked on S-04's delete); the pre-existing `useEffect` unmount/abort gap.

## Architecture / Approach

The mutation is routed through the aggregate root. `AnalysisService.updateClauseDecision` loads the `Analysis` with the *same* owner-scoped query the read path uses, then asks the aggregate to mutate its own clause; JPA dirty-checking flushes on commit. A clause on someone else's analysis is unreachable because the parent load already returned empty — ownership is inherited by construction, not re-checked. On the client, a segmented `Button` row updates React state optimistically, fires the `PATCH` through the existing `apiFetch` (which already attaches the CSRF header to non-GET requests), and rolls back just that clause on failure.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Backend write path | Aggregate method, DTO, service, `PATCH` endpoint, CORS fix, plus aggregate / isolation / boundary+preflight / flow tests | Dropping `@Transactional` loses the write **silently** — the 200 response echoes the un-persisted value, so only a separate `GET` catches it |
| 2. Frontend decision control | Polish labels, typed wrapper, segmented control with optimistic update and inline rollback | Capturing the prior decision *after* `setAnalysis` would roll back to the wrong value |
| 3. End-to-end coverage | A Playwright spec asserting the decision persists across a reload | Seeds against the live LLM, so it is a local-only gate with a non-deterministic clause count |

**Prerequisites:** S-01 (`analyze-and-save-contract`) — done. Phase 1's CORS fix must land before Phase 2 is exercisable in a browser.
**Estimated effort:** ~1–2 sessions across 3 phases. Small surface; the care is in the tests.

## Open Risks & Assumptions

- **Two failure modes are invisible to a naive test suite,** and plan review added an automated guard for each. (1) Without `PATCH` in `setAllowedMethods` (`SecurityConfig.java:75`) the browser rejects the request before Spring sees it — and `MockMvc` sends no `Origin` header, so every backend test still passes. Guarded by an `OPTIONS` preflight assertion in `AuthBoundaryMatrixTest`. (2) Without `@Transactional` the mutation is never flushed, yet the endpoint returns `200` with the new value read off the detached object. Guarded by a `PATCH` → separate-`GET` round-trip in `AnalysisFlowTest`.
- **This is the codebase's first mutation of a persisted entity.** The convention has been "read-only after construction." The package-private setter is a deliberate, narrow break — worth flagging in review so it doesn't become a precedent for public setters everywhere.
- **`AuthBoundaryMatrixTest` looks more like a matrix than it is.** Its `@MethodSource` fires GETs only, so the `PATCH` route needs two hand-written tests, not a new row. Easy to miss.
- Assumes the Polish decision labels (`Akceptuję` / `Do negocjacji` / `Odrzucam` / `Bez decyzji`) are acceptable copy; they are a design choice, easily changed in one map.

## Success Criteria (Summary)

- A user can mark each clause, change their mind, and clear a decision — and it is still there after a reload.
- A second user cannot read *or* change another user's clause, and cannot tell a foreign id from a nonexistent one.
- The "not legal advice" disclaimer remains visible wherever the analysis result is shown.

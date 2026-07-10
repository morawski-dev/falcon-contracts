<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Delete an analysis (S-04)

- **Plan**: `context/changes/delete-analysis/plan.md`
- **Mode**: Deep
- **Date**: 2026-07-10
- **Verdict**: REVISE → **SOUND** (after triage; all 5 findings fixed)
- **Findings**: 1 critical, 2 warnings, 2 observations

## Verdicts

| Dimension | Verdict (at review) | After fixes |
|-----------|---------------------|-------------|
| End-State Alignment | PASS | PASS |
| Lean Execution | WARNING | PASS |
| Architectural Fitness | WARNING | PASS |
| Blind Spots | WARNING | PASS |
| Plan Completeness | FAIL | PASS |

## Grounding

15/15 paths ✓, 6/6 symbols ✓, brief↔plan ✓, Progress↔Phase ✓ (4 phases, 21 items, all matched).

Notes: `frontend/src/app/analyses/[id]/page.tsx` initially reported missing — a PowerShell `Test-Path` artifact (`[id]` parsed as a character-class wildcard); confirmed present via Glob. `docs/reference/contract-surfaces.md` does not exist, so the contract-surfaces check was skipped per its opt-in convention.

Five riskiest claims were verified by sub-agent against the code: four CONFIRMED, one CONTRADICTED (see F1).

## Findings

### F1 — The plan's load-bearing assertion has no mechanism

- **Severity**: ❌ CRITICAL
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Completeness
- **Location**: Phase 2 → Tests → AnalysisFlowTest; Progress item 2.2
- **Detail**: Test 2.2 must assert all three tables hold zero rows for the deleted id — the plan's only cascade regression gate, and it explicitly warns that asserting the `analyses` row alone "would pass even if the cascade broke." But no `ClauseRepository` or `NegotiationPointRepository` exists (only `AnalysisRepository` and `UserRepository`), and a grep of the entire test tree for `JdbcTemplate|EntityManager|TestEntityManager|nativeQuery` returns zero matches. Existing tests reach child rows only by walking the entity graph — `analysis.getNegotiationPoints()` inside a `TransactionTemplate` (`AnalysisFlowTest.java:96-105`) — which is precisely impossible once the parent row is deleted. The plan names no mechanism.
- **Fix A ⭐ Recommended**: Autowire a `JdbcTemplate` in the test class only.
  - Strength: The only way to observe rows whose parent no longer exists; test-scoped, so no production repository is created and F-01's invariant (`SecurityUtils.java:7-12`) stays intact.
  - Tradeoff: First raw SQL in a suite that has stayed at the repository/entity-graph level.
  - Confidence: HIGH — `JdbcTemplate` is on the classpath via `spring-boot-starter-data-jpa` and binds to the Testcontainers `DataSource` already wired by `@ServiceConnection`.
  - Blind spot: Whether the team wants raw SQL in tests at all — a taste call.
- **Fix B**: Add `ClauseRepository` + `NegotiationPointRepository`.
  - Strength: Stays in the Spring Data idiom; `countByAnalysisId(...)` reads cleanly.
  - Tradeoff: Adds two production repositories to satisfy a test; `research.md` records their absence as deliberate.
  - Confidence: MEDIUM — works, but trades a documented invariant for test convenience.
  - Blind spot: Whether a count-by-parent accessor is genuinely inside the invariant's scope.
- **Decision**: FIXED via Fix A — Phase 2's `AnalysisFlowTest` contract now names the `JdbcTemplate` mechanism and explains why not a repository; "What We're NOT Doing" gained a matching guard against creating the child repositories.

### F2 — Changeset 004's SET NULL and CASCADE collide in one statement, and nothing exercises it before Phase 2

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots
- **Location**: Phase 1 → Manual Verification (Progress 1.5)
- **Detail**: After 004 lands, a single `DELETE FROM analyses` asks Postgres to both `SET NULL` the `negotiation_points.clause_id` (cascading from the clauses deletion) and `DELETE` those same rows (cascading from `analysis_id`), within one statement. Whether those referential actions compose cleanly, or raise `tuple to be updated was already modified by an operation triggered by the current command`, is exercised by nothing in the plan. Phase 1's manual check (1.5) deletes a single `clauses` row, testing `SET NULL` in isolation only. The first thing to exercise the combination was Phase 2's test 2.2 — which per F1 had no mechanism. The two findings compound: the plan's only check on its riskiest interaction was a test that could not be written.
- **Fix**: Strengthen Phase 1's manual verification to hand-delete a full `analyses` row (seeded with a clause and a linked negotiation point) in one statement against the Compose DB, asserting all three tables empty.
  - Strength: Moves the riskiest unverified claim into the phase designed to fail loudly and in isolation.
  - Tradeoff: None material; it replaces a weaker manual check.
  - Confidence: HIGH — exactly the class of failure Phase 1 was sequenced first to catch.
  - Blind spot: None significant.
- **Decision**: FIXED — new manual criterion added to Phase 1, with Progress item 1.6.

### F3 — The bulk delete's correctness rests entirely on the DB cascade, and the plan never says so

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Architectural Fitness
- **Location**: Critical Implementation Details; Phase 2 → Repository
- **Detail**: A JPQL bulk delete bypasses the persistence-context lifecycle, so `Analysis.java:43-47`'s `cascade = CascadeType.ALL, orphanRemoval = true` never fires. Children are removed solely because `002-create-analyses.yaml:115-128` declares `onDelete: CASCADE` on the two `analysis_id` FKs. The plan called the bypass "harmless" but never stated the consequence: for this operation the JPA cascade annotations are decorative, and the feature breaks silently if a future schema cleanup drops the FK cascades on the reasonable-sounding grounds that "JPA already cascades."
- **Fix**: One paragraph in Critical Implementation Details, and a comment on `deleteOwned`, recording that child cleanup depends on the DB-level `ON DELETE CASCADE` — not on the JPA cascade, which a bulk JPQL delete never triggers.
  - Strength: Documents the invariant at the exact place a future reader would be tempted to break it.
  - Tradeoff: None; it's a comment.
  - Confidence: HIGH — verified no `@SQLDelete`, no inheritance, no `@ElementCollection`, so the bulk delete is legal and the JPA cascade is genuinely inert.
  - Blind spot: None significant.
- **Decision**: FIXED — added to Critical Implementation Details and to the Phase 2 repository contract (which also now notes `@Modifying` needs importing; `@Query`/`@Param` already are).

### F4 — "Outside this feature's blast radius" is applied inconsistently

- **Severity**: 💭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Lean Execution
- **Location**: What We're NOT Doing ↔ Phase 4
- **Detail**: The exclusion of the two-phase teardown cleanup was justified by it touching "two test classes outside this feature's blast radius." Phase 4 then edits two other test files for a cleanup with the same property. Both choices are defensible, but the stated principle does not distinguish them, so it reads as post-hoc.
- **Fix**: Restate the exclusion on the real distinction — the e2e specs named S-04 as their unblocker and are broken without it; the backend teardowns merely become redundant and still pass.
- **Decision**: FIXED — rationale rewritten as "still works but is now superfluous" (future cleanup) versus "cannot function until this slice lands" (this slice's responsibility).

### F5 — Nothing proves the advice narrowing actually narrowed

- **Severity**: 💭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots
- **Location**: Phase 1 → item 3; Progress 1.3
- **Detail**: Progress 1.3 offers `AuthFlowTest` as the regression proof. It does still pass — but only because its 409 and 401 both originate in `AuthController`, which remains in scope. It proves the advice still covers auth; it cannot detect the reverse. Reverting `assignableTypes` to a bare `@RestControllerAdvice` would leave the suite fully green.
- **Fix**: State the gap explicitly — no controller other than `AuthController` can currently raise `DataIntegrityViolationException` (`analyses` carries only a PK and FKs, no unique constraint), so there is no violation to provoke and no honest assertion to write. Accept it knowingly rather than let 1.3 imply coverage it lacks.
- **Decision**: FIXED — Phase 1's item 3 now carries an explicit "On the limits of the regression proof" note.

## Verified claims (no finding)

- Narrowing `AuthExceptionHandler` to `AuthController` breaks no existing test. `AuthenticationException` is raised only at `AuthController.java:72`; the anonymous 401s in `AuthBoundaryMatrixTest.java:45,57,69` come from the security filter chain and never reach a controller advice; `UserRepositoryTest.java:56` asserts at the JPA layer.
- The bulk JPQL delete is legal on `Analysis` — no `@ElementCollection`, `@JoinTable`, inheritance, or `@SQLDelete`. `ownerId` is a mapped field (`Analysis.java:27-28`), so `a.ownerId` resolves.
- `AnalysisIsolationTest`'s single-phase `@AfterEach` (`:51-55`) survives the new cross-user DELETE test, because that test seeds clauses only — no negotiation points to trip `fk_negotiation_points_clause`.
- `AuthBoundaryMatrixTest`'s `@MethodSource("protectedGetRoutes")` (`:32-39`) covers GET only, confirming the DELETE cases must be hand-written.
- The CORS preflight precedent exists: `corsPreflightPermitsPatch` (`AuthBoundaryMatrixTest.java:87-94`) asserts `Access-Control-Allow-Methods` contains `PATCH`. The `DELETE` analogue has a direct template.
- `apiFetch` returns the raw `Response` and does not parse JSON (`api.ts:44`), so a `204` with no body is safe; a `deleteAnalysis(): Promise<void>` that ignores the body matches the existing style.
- `analyses/[id]/page.tsx` already imports and instantiates `useRouter` (`:4`, `:25`) and has a title `CardHeader` (`:140-144`) as a natural slot for the destructive button.

<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Identity & Per-User Isolation (F-01)

- **Plan**: `context/changes/identity-and-isolation/plan.md`
- **Mode**: Deep
- **Date**: 2026-07-04
- **Verdict**: REVISE → SOUND (all findings fixed)
- **Findings**: 0 critical, 5 warnings, 0 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | WARNING → PASS (F1, F3, F4 fixed) |
| Plan Completeness | WARNING → PASS (F2, F5 fixed) |

## Grounding
9/9 paths ✓, Spring Security 7.0.6 APIs 6/6 verified (`csrf().spa()`, `HttpStatusEntryPoint`, `ProviderManager`/`DaoAuthenticationProvider`, `createDelegatingPasswordEncoder()`, `HttpSessionSecurityContextRepository.saveContext`, spring-security-test `user(...)`/`csrf()`) ✓, brief↔plan ✓. `lessons.md` / `contract-surfaces.md` absent — checks skipped. Blast radius: scaffold confirmed empty of domain/security code, no collisions.

## Findings

### F1 — The reused isolation primitive ships untested

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Blind Spots (test coverage)
- **Location**: Phase 2 — Isolation primitive / Phase 2 tests
- **Detail**: `SecurityUtils.currentUserId()` is the contract S-01–S-04 inherit, but nothing in F-01 exercises it — `/api/auth/me` reads the principal via `@AuthenticationPrincipal`, not the helper. It could ship broken and F-01 stays green.
- **Fix A ⭐ Recommended**: Add a focused `SecurityUtilsTest` (returns id from a populated context; throws when anonymous).
  - Strength: Tests the reused primitive in isolation incl. the guard path; cheap.
  - Tradeoff: A little `SecurityContextHolder` setup boilerplate.
  - Confidence: HIGH — standard pattern.
  - Blind spot: None significant.
- **Fix B**: Implement `/me` via `SecurityUtils.currentUserId()` so Phase-2 `/me` tests cover it.
  - Strength: No new test.
  - Tradeoff: Couples `/me` to the static helper; skips the anonymous branch.
  - Confidence: HIGH.
- **Decision**: FIXED (Fix A — added `SecurityUtilsTest`, Phase 2 automated criterion + Progress 2.4)

### F2 — Repository test annotation unspecified; bare @DataJpaTest hard-fails

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness / Blind Spots
- **Location**: Phase 1 — Repository test (criterion 1.2)
- **Detail**: No embedded DB is on the classpath, so a conventional `@DataJpaTest` (which defaults to replacing the DataSource with an embedded one) fails at startup. The plan didn't name the test annotation.
- **Fix**: Specify the existing `@SpringBootTest @Import(TestcontainersConfiguration.class)` pattern; note it isn't auto-rolled-back.
- **Decision**: FIXED (updated Testing Strategy `UserRepositoryTest` bullet)

### F3 — Manual verification needs OPENROUTER_API_KEY or the app won't boot

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots (error paths)
- **Location**: Phases 1/2/4 — Manual Verification
- **Detail**: `application.properties` has `${OPENROUTER_API_KEY}` with no default; `spring-boot:run` fails placeholder resolution at startup when unset, even though F-01 makes no LLM calls. Tests are fine (test profile hardcodes a dummy key).
- **Fix**: Note the prereq (export a dummy `OPENROUTER_API_KEY`); avoid an empty `${...:}` default that would mask real misconfig later.
- **Decision**: FIXED (added Local-run prerequisites to Testing Strategy + Phase 1 manual step reference)

### F4 — Manual verification assumes spring-boot:run auto-starts Postgres

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Blind Spots / Contradiction
- **Location**: Phase 1 — Manual Verification (1.3)
- **Detail**: `backend/compose.yaml` was deleted and a root `docker-compose.yaml` added (uncommitted); `spring-boot:run` from `backend/` may no longer auto-start Postgres.
- **Fix**: Add a Postgres-up prereq (resolve relocation, `docker compose up`, or set `spring.docker.compose.file`).
- **Decision**: FIXED (folded into Local-run prerequisites)

### F5 — Progress↔Phase count mismatch in Phase 2 Manual

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Completeness
- **Location**: Phase 2 — Manual Verification vs `## Progress` 2.4
- **Detail**: Two manual success criteria collapsed into a single Progress item, so per-item tracking is off by one.
- **Fix**: Split into 2.5 (explicit chain / no default password) and 2.6 (CORS preflight).
- **Decision**: FIXED (Progress Phase 2 renumbered 2.1–2.6)

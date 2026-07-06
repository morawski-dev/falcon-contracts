<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Identity & Per-User Isolation (F-01)

- **Plan**: `context/changes/identity-and-isolation/plan.md`
- **Scope**: Full plan (Phases 1–4)
- **Date**: 2026-07-05
- **Verdict**: APPROVED
- **Findings**: 0 critical, 1 warning, 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Findings

### F1 — Unhandled DataIntegrityViolationException on concurrent duplicate registration

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (ties into Plan Adherence — the plan's Phase 3 contract calls for a uniform `{"error": "..."}` JSON body on all three error cases)
- **Location**: `backend/src/main/java/com/morawski/dev/falcon/auth/AuthController.java:58-64` (register), `backend/src/main/java/com/morawski/dev/falcon/auth/AuthExceptionHandler.java:19-22` (only maps `AuthenticationException`)
- **Detail**: `register()` checks `existsByEmail()` then calls `save()` — a TOCTOU gap. Two concurrent requests for the same email can both pass the pre-check; the DB's unique constraint (`uk_users_email`) correctly prevents a duplicate row (confirmed by `UserRepositoryTest.duplicateEmailViolatesUniqueConstraint`), so no data corruption occurs. But the losing request's `save()` throws an unhandled `DataIntegrityViolationException`, which falls through to Spring Boot's default error handling as a raw `500` with a default-shaped body — instead of the clean `409` + `{"error": "Email already in use"}` the same scenario produces via the `existsByEmail` pre-check path. `AuthExceptionHandler` only has an `@ExceptionHandler(AuthenticationException.class)`; nothing catches this case anywhere in the codebase (confirmed via grep).
- **Fix**: Add `@ExceptionHandler(DataIntegrityViolationException.class)` to `AuthExceptionHandler` mapping to `409` with the same "Email already in use" message — consistent with the existing handler pattern, no new file needed.
- **Decision**: FIXED — handler added to `AuthExceptionHandler.java`; `AuthFlowTest`/`AuthControllerTest` re-run green (6/6).

### F2 — Ambiguous error message if auto-login fails right after successful registration

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (UX/reliability)
- **Location**: `frontend/src/app/(auth)/register/page.tsx:32-36`
- **Detail**: `handleSubmit` calls `register()` then immediately `login()`. If registration succeeds but the follow-up login fails (network blip, cookie issue), the catch block shows "Nie udało się utworzyć konta" (failed to create account) even though the account was created — the user may retry and hit a confusing `409`.
- **Fix**: Not urgent for MVP. If polished later, distinguish the two failure modes — e.g., on a post-register login failure, redirect to `/login` with a "please log in" message instead of implying registration failed.
- **Decision**: SKIPPED — MVP-scale edge case, low likelihood; not worth the added branching now.

## Supporting evidence

- **Plan drift check**: every planned file across all 4 phases matches its stated Contract (entity fields, migration columns, repository methods, security beans, CSRF/CORS config, DTO validation, the session-persisting login, the frontend fetch wrapper and pages). The single highest-risk contract in the whole plan — `securityContextRepository.saveContext(context, request, response)` after programmatic login — is present exactly as specified and is exercised by `AuthFlowTest.registerLoginMeLogoutFlowSucceeds`, which asserts a session is actually created.
- **Documented, expected deviation (not drift)**: the plan specifies `frontend/src/middleware.ts`; the repo has `frontend/src/proxy.ts` exporting `proxy()` instead of `middleware()`. This is the correct adaptation to Next.js 16.2.10's deprecation of the `middleware.ts` convention in favor of `proxy.ts` (same `matcher`/behavior) — verified against the framework's own migration docs and already explained in the Phase 4 commit message.
- **Security spot-checks (all clean)**: password never logged or returned in any response; BCrypt via `PasswordEncoderFactories.createDelegatingPasswordEncoder()`; the 401 handler returns an identical generic message for unknown-email vs wrong-password (no user enumeration); CORS is scoped to exactly `http://localhost:3000` with limited methods/headers, not wildcarded; CSRF (`csrf().spa()`) verified against the actual Spring Security 7.0.6 bytecode as coherent and not disabled; no hardcoded secrets; no JPA injection risk (derived queries only); email lowercase-normalization is consistent between register and login.
- **`TestcontainersConfiguration` visibility widening** (package-private → `public class`): confirmed test-scope-only, purely additive, no behavior change — done solely so `auth`-subpackage integration tests could `@Import` it; not a red flag.
- **Success criteria**: all automated checks (backend `./mvnw clean package` — 12/12 tests; frontend `pnpm build` + `pnpm lint`) were run and passed during implementation, and no code has changed since. All manual criteria across all 4 phases were confirmed by the user during the implementation walkthrough. Plan's `## Progress` section: every checkbox in all 4 phases is `[x]` with the correct commit SHA appended (fee2cd9, 713c794, 4c0fd9f, a7ed848), plus a closing epilogue commit (22731c4).

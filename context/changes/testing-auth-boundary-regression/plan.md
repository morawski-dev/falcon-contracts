# Auth Boundary Regression — Anonymous→401 Route Matrix Implementation Plan

## Overview

Generalize the single existing anonymous→401 assertion (`AuthControllerTest.anonymousMeReturns401`)
into a full behavioral route matrix that proves default-deny holds for every protected route — app
and framework — locks the exact 4-entry permit-list, and locks the "401, not a redirect" entry-point
contract. This is rollout Phase 1 of `context/foundation/test-plan.md` (Risk #5: a gated endpoint
silently becomes reachable without authentication after a security-config change).

## Current State Analysis

The auth boundary is a single explicit `SecurityFilterChain`
(`backend/src/main/java/com/morawski/dev/falcon/auth/SecurityConfig.java:31-52`): CSRF via
`csrf.spa()` (`:36`), a 4-matcher permit-list (`OPTIONS /**`, `GET /api/auth/csrf`,
`POST /api/auth/register`, `POST /api/auth/login`, `:39-41`), `.anyRequest().authenticated()`
(`:42`) as default-deny, and `HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)` (`:43-44`) as the entry
point — deliberately not `formLogin`/`httpBasic`, so the API returns 401 rather than a redirect.

Only two `@RestController`s exist, and two of the three protected app routes
(`POST /api/analyses`, `GET /api/analyses/{id}`, `AnalysisController.java:27,34`) were secured with
**zero edits to `SecurityConfig`** — they inherited protection purely from `.anyRequest().authenticated()`
(confirmed by `git diff` across the archived `analyze-and-save-contract` commits and that change's own
research: "new `/api/analyses/**` endpoints require a session with no config change"). That inheritance
is exactly what's currently unguarded.

Today's only boundary test is `AuthControllerTest.anonymousMeReturns401`
(`AuthControllerTest.java:38-42`) — one endpoint, asserted once. `GET /api/auth/csrf` — one of the
four permit-listed routes — has **no test coverage at all** (checked: neither `AuthFlowTest` nor
`AuthControllerTest` ever calls it; the frontend primes it, but no backend test does).

## Desired End State

A new test class proves, for the current route set and for a route that doesn't exist yet, that
anonymous access is rejected with 401 (never a redirect, never silently 200), that the 4 permit-listed
routes remain reachable, and that the one documented exception (`POST /api/auth/logout`, which
resolves before the authorization filter runs) is asserted explicitly rather than silently working
around it. The now-redundant single-endpoint test is removed in favor of the generalized matrix.

**Verify**: `cd backend && ./mvnw test -Dtest=AuthBoundaryMatrixTest` and `cd backend && ./mvnw test`
(full suite) both pass.

### Key Discoveries:

- `SecurityConfig.java:39-41` permit-list has **4** matchers, not the 3 "bootstrap set" the change
  brief named — `OPTIONS /**` is the 4th, and it's CORS infrastructure, not an app-route exception.
- CSRF (`SecurityConfig.java:36`) runs `CsrfFilter` *before* `AuthorizationFilter`: an anonymous POST
  with no token gets **403 (CSRF)**, not 401; `.with(csrf())` is required to reach the *auth* boundary.
- `POST /api/auth/logout` (`SecurityConfig.java:45-49`) is **not** permit-listed yet returns **204**
  (or 403 without a token) for an anonymous caller — `LogoutFilter` runs before `AuthorizationFilter`,
  so logout has no auth gate by design.
- Unmapped paths return **401, not 404** — `AuthorizationFilter` runs before `DispatcherServlet`
  routing, so `.anyRequest()` matches regardless of whether a handler exists.
- `spring-boot-starter-actuator` is on the classpath (`backend/pom.xml:36`); with no `management.*`
  overrides, only `/actuator` and `/actuator/health` are exposed over HTTP, and both fall to
  default-deny → 401.
- `GET /api/auth/csrf` has zero existing test coverage (a genuine gap this plan closes); register and
  login permit-list reachability are already fully proven by `AuthFlowTest` (both calls are made with
  no `.with(user(...))`, i.e. truly anonymous) — no need to duplicate that coverage.

## What We're NOT Doing

- Not reflectively enumerating `RequestMappingHandlerMapping` to auto-discover future routes — a
  static matrix + one unmapped-path probe is the cheaper, cost×signal-aligned choice for a 2-controller
  MVP (per user decision).
- Not asserting on response bodies — only status codes (matches test-plan §7's rationale: bodies are
  either empty or framework-rendered and not part of the contract being protected).
- Not adding a CI gate / pipeline change — that's rollout Phase 4 ("Quality-gate wiring"); this phase
  only adds the tests to the existing `./mvnw test` run.
- Not touching `SecurityConfig.java` itself — this phase is test-only; no production code changes.
- Not re-testing register/login success/failure paths — already exhaustively covered by
  `AuthFlowTest`/`AuthControllerTest`; duplicating them here would be redundant.
- Not filling in `test-plan.md` §6.3 (the cookbook entry) or its "Per-rollout-phase notes" — per the
  test-plan's own convention, `/10x-implement` appends that note after the phase lands, not the plan.

## Implementation Approach

One new test class, `AuthBoundaryMatrixTest`, alongside the existing auth tests, using the same
`@SpringBootTest @AutoConfigureMockMvc @Import(TestcontainersConfiguration.class)` slice every other
HTTP test in the suite already uses (no new test-slice convention introduced). Anonymous-rejected
requests never reach the DB, so the class needs no `@AfterEach` cleanup. A single
`@ParameterizedTest` covers the GET-protected routes (including the unmapped-path probe); the sole
protected POST route, the CORS preflight, the csrf-endpoint gap, and the logout exception each get one
dedicated, named test method — parameterizing a generic multi-verb harness for a single POST case
would be more machinery than the current route count justifies.

## Critical Implementation Details

- **CSRF-before-auth ordering governs every POST row.** `CsrfFilter` precedes `AuthorizationFilter`
  in the chain, so an anonymous POST with no token never reaches the authentication check — it 403s
  in `CsrfFilter` itself. Every protected-POST-row that intends to prove the *auth* boundary must send
  `.with(csrf())`; the one row that deliberately omits it exists specifically to prove the CSRF
  boundary (403), not the auth one.
- **The 401 path uses `setStatus()`, not `sendError()`.** `HttpStatusEntryPoint` sets the status
  directly, so there is no container ERROR-dispatch re-entry — MockMvc's observed status is identical
  to a real server's for every 401 row. This is why asserting status alone (never body) is sufficient
  and safe.
- **The CORS preflight test only proves anything if both `Origin` and
  `Access-Control-Request-Method` headers are set.** A bare `MockMvc.perform(options(path))` with no
  headers never engages `CorsFilter` — it falls through to the `OPTIONS /**` permitAll rule and MVC's
  default OPTIONS handling, which would return 200 even if CORS itself were misconfigured. The test
  must send both headers to actually exercise the CORS path.
- **The logout row still needs `.with(csrf())`.** `LogoutFilter` runs after `CsrfFilter` in the chain,
  so an anonymous logout without a token still 403s; only *with* a valid token does it reach
  `LogoutFilter` and return 204 unconditionally (no auth gate).

## Phase 1: Auth Boundary Regression Suite

### Overview

Add the generalized route matrix and remove the single-endpoint test it supersedes.

### Changes Required:

#### 1. New auth-boundary matrix test

**File**: `backend/src/test/java/com/morawski/dev/falcon/auth/AuthBoundaryMatrixTest.java`

**Intent**: Prove, in one place, that every protected route (current app routes, the actuator
endpoint, and an unmapped path) rejects an anonymous caller with 401; that the permit-listed
`GET /api/auth/csrf` remains reachable (closing the existing coverage gap); that CSRF fires before
authentication on state-changing verbs; that the CORS preflight rule works as CORS infrastructure
rather than an auth exception; and that the logout carve-out is a documented, deliberate exception to
default-deny rather than a silent gap.

**Contract**: `@Import(TestcontainersConfiguration.class) @SpringBootTest @AutoConfigureMockMvc`
(mirrors `AuthControllerTest`/`AuthFlowTest`). Test methods:

- `@ParameterizedTest @MethodSource("protectedGetRoutes")` — one row each for `GET /api/auth/me`,
  `GET /api/analyses/1`, `GET /actuator/health`, and `GET /api/__does_not_exist__` (the unmapped-path
  probe) — each asserts `status().isUnauthorized()`.
- `anonymousPostWithoutCsrfReturns403()` — `POST /api/analyses` with no `.with(csrf())` →
  `status().isForbidden()`.
- `anonymousPostWithCsrfReturns401()` — `POST /api/analyses` with `.with(csrf())` →
  `status().isUnauthorized()`.
- `anonymousCsrfEndpointIsReachable()` — `GET /api/auth/csrf` → `status().isNoContent()` (the coverage
  gap this phase closes).
- `corsPreflightIsPermitted()` — `OPTIONS /api/analyses` with `Origin: http://localhost:3000` and
  `Access-Control-Request-Method: POST` headers → `status().isOk()` and
  `header().exists("Access-Control-Allow-Origin")`.
- `logoutIsReachableAnonymouslyByDesign()` — `POST /api/auth/logout` with `.with(csrf())`, anonymous
  → `status().isNoContent()`, with a one-line comment explaining `LogoutFilter` precedes
  `AuthorizationFilter` by design (this is the intentional exception to the matrix, not a gap).

#### 2. Remove the superseded single-route assertion

**File**: `backend/src/test/java/com/morawski/dev/falcon/auth/AuthControllerTest.java`

**Intent**: `anonymousMeReturns401` (`:38-42`) is exactly the "assert one endpoint and generalize"
pattern the new matrix replaces; keeping both would be redundant coverage of the same fact.

**Contract**: Delete the `anonymousMeReturns401` test method. Leave
`authenticatedMeReturnsCallerIdentity` untouched.

### Success Criteria:

#### Automated Verification:

- New test class passes: `cd backend && ./mvnw test -Dtest=AuthBoundaryMatrixTest`
- `AuthControllerTest` still passes with the reduced scope: `cd backend && ./mvnw test -Dtest=AuthControllerTest`
- Full backend suite passes with no regressions: `cd backend && ./mvnw test`

#### Manual Verification:

- Grep the repo for `anonymousMeReturns401` to confirm nothing else referenced the removed method
  before deleting it.
- "Test the test": temporarily comment out `.anyRequest().authenticated()` in `SecurityConfig.java`
  (or change one permit-list matcher to `permitAll()`), re-run `AuthBoundaryMatrixTest`, confirm it
  **fails**, then revert. This confirms the suite actually catches the regression it exists to catch,
  not just that it's green by coincidence.

**Implementation Note**: This is the only phase in this plan — after both verification sets pass,
the change is complete; no further phases follow.

---

## Testing Strategy

### Unit Tests:

- N/A — this phase is itself the test suite; there is no separate unit layer beneath it.

### Integration Tests:

- The `@ParameterizedTest` GET-route matrix (default-deny across app + framework + unmapped routes).
- The CSRF-ordering pair (no-token → 403, with-token → 401) on the one protected POST route.
- The permit-list coverage gap close (`GET /api/auth/csrf` → 204).
- The CORS preflight behavior (real preflight headers → 200 + CORS header).
- The logout carve-out, documented as a deliberate exception.

### Manual Testing Steps:

1. Run the full backend suite and confirm no regressions.
2. Perform the "test the test" sanity check described in Manual Verification above, then revert the
   temporary change.

## Performance Considerations

`AuthBoundaryMatrixTest` imports only `TestcontainersConfiguration` — the same configuration as
`AuthControllerTest` and `AuthFlowTest`. Spring's test context caching should reuse the already-running
Testcontainers Postgres container and `ApplicationContext` across these classes rather than starting a
new one, so this phase adds negligible suite runtime despite using the full-context slice.

## Migration Notes

Not applicable — no schema or data changes; test-only phase.

## References

- Research: `context/changes/testing-auth-boundary-regression/research.md`
- Security config: `backend/src/main/java/com/morawski/dev/falcon/auth/SecurityConfig.java:31-52`
- Existing pattern to mirror: `backend/src/test/java/com/morawski/dev/falcon/auth/AuthControllerTest.java`,
  `backend/src/test/java/com/morawski/dev/falcon/auth/AuthFlowTest.java`
- Historical grounding: `context/archive/2026-07-04-identity-and-isolation/plan.md:51` (401-not-redirect
  decision), `context/archive/2026-07-06-analyze-and-save-contract/research.md:119` (auto-gating with
  no config change)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Auth Boundary Regression Suite

#### Automated

- [x] 1.1 New test class passes: `./mvnw test -Dtest=AuthBoundaryMatrixTest`
- [x] 1.2 `AuthControllerTest` still passes with reduced scope
- [x] 1.3 Full backend suite passes with no regressions

#### Manual

- [x] 1.4 Grep confirms nothing else referenced the removed `anonymousMeReturns401` method
- [x] 1.5 "Test the test" — a deliberately broken permit-list/default-deny config makes the suite fail, then revert

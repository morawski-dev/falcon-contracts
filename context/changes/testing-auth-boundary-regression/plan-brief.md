# Auth Boundary Regression — Plan Brief

> Full plan: `context/changes/testing-auth-boundary-regression/plan.md`
> Research: `context/changes/testing-auth-boundary-regression/research.md`

## What & Why

Falcon's entire auth boundary is one explicit `SecurityFilterChain`, and new routes get protected
for free by inheriting `.anyRequest().authenticated()` — no per-route declaration, no review trigger.
That's elegant, but two of the three protected routes in the app today were secured with **zero
security-config edits**, and nothing currently proves the inheritance holds. This phase (test-plan
Risk #5) closes that gap: a route matrix that fails the instant default-deny erodes, instead of
discovering it in production.

## Starting Point

One anonymous→401 test exists (`AuthControllerTest.anonymousMeReturns401`) — a single endpoint,
asserted once, which is itself the "test one and generalize" anti-pattern this phase replaces. One
permit-listed route (`GET /api/auth/csrf`) has **no test coverage at all**. No test currently proves
the entry point is 401-not-a-redirect as a standing property, or that CSRF's filter ordering doesn't
silently change what "anonymous" means per HTTP method.

## Desired End State

A single new test class proves: every protected route (current app routes, the actuator endpoint, and
an arbitrary route that doesn't exist yet) rejects anonymous callers with 401; all 4 permit-listed
routes stay reachable; the one legitimate exception (anonymous logout, which resolves before the auth
filter runs) is asserted explicitly rather than silently tolerated; and the redundant single-endpoint
test is removed.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
|---|---|---|---|
| Test slice | `@SpringBootTest` + Testcontainers (not `@WebMvcTest`) | Matches every existing HTTP test; avoids introducing a first-of-its-kind slice pattern with zero precedent in this codebase. | Plan |
| "New route defaults to authenticated" | Static matrix + one unmapped-path probe | Cheapest option that still proves default-deny as a standing property; reflective route enumeration was rejected as more machinery than a 2-controller MVP needs. | Plan |
| `OPTIONS /**` coverage | Excluded from the 401 matrix; separate CORS preflight test with real headers | A bare `options()` call proves nothing about CORS — needs `Origin` + `Access-Control-Request-Method` headers to actually exercise the rule. | Plan |
| `POST /api/auth/logout` (204, not 401) | One explicit, documented row in the new test | Keeps the one deliberate exception to default-deny visible in the same file as the rule, instead of scattered or silently "fixed" later. | Plan |
| `/actuator/health` coverage | Included as one matrix row | Near-zero cost; proves default-deny isn't special-cased to app controllers only. | Plan |

## Scope

**In scope:**
- One new test class (`AuthBoundaryMatrixTest`) covering the full anonymous-caller behavior matrix
- Removing the now-redundant `anonymousMeReturns401` single-endpoint test
- Closing the `GET /api/auth/csrf` coverage gap

**Out of scope:**
- Any change to `SecurityConfig.java` itself (test-only phase)
- CI/pipeline wiring (that's rollout Phase 4)
- Reflective route auto-discovery
- Re-testing register/login success/failure paths (already covered by `AuthFlowTest`)

## Architecture / Approach

One test file, same slice convention as the rest of the suite (`@SpringBootTest` + `@AutoConfigureMockMvc`
+ Testcontainers). A `@ParameterizedTest` handles the GET-protected routes uniformly; the single
protected POST route, the CSRF-ordering proof, the CORS preflight, the csrf-endpoint gap, and the
logout exception each get one dedicated test method — no generic multi-verb harness, since there's
only one POST case to cover today.

## Phases at a Glance

| Phase | What it delivers | Key risk |
|---|---|---|
| 1. Auth Boundary Regression Suite | The full route matrix + removal of the superseded single-endpoint test | A "test the test" step is required to confirm the suite actually fails when default-deny is broken, not just green by coincidence |

**Prerequisites:** None — buildable today against the existing F-01 auth slice.
**Estimated effort:** ~1 session, single phase, one new test file + one small deletion.

## Open Risks & Assumptions

- Assumes Spring's test-context caching reuses the existing Testcontainers Postgres container across
  this class and the other `@SpringBootTest` auth tests (no new container startup cost) — not verified
  empirically, only inferred from identical `@Import` configuration.
- The static matrix does not automatically cover a *future* controller that's added and mistakenly
  `permitAll()`'d — only the unmapped-path probe and manual matrix maintenance guard against that.

## Success Criteria (Summary)

- `./mvnw test -Dtest=AuthBoundaryMatrixTest` and the full `./mvnw test` suite both pass.
- A deliberately broken permit-list/default-deny config makes the new suite fail (confirmed manually,
  then reverted).
- No duplicate coverage: the redundant single-endpoint test is gone, and register/login paths aren't
  re-tested.

---
date: 2026-07-06T16:39:40+0200
researcher: Mateusz Morawski
git_commit: 23923259ecd805162146cc037b74ce083eef9166
branch: main
repository: falcon-contracts
topic: "Auth boundary regression — anonymous→401 route matrix (test-plan Phase 1, Risk #5)"
tags: [research, codebase, security, spring-security, csrf, default-deny, mockmvc, auth-boundary]
status: complete
last_updated: 2026-07-06
last_updated_by: Mateusz Morawski
---

# Research: Auth boundary regression — anonymous→401 route matrix

**Date**: 2026-07-06T16:39:40+0200
**Researcher**: Mateusz Morawski
**Git Commit**: 23923259ecd805162146cc037b74ce083eef9166 (`2392325`)
**Branch**: main
**Repository**: falcon-contracts

## Research Question

Rollout Phase 1 of `context/foundation/test-plan.md` ("Auth boundary regression", Risk #5): a gated
endpoint silently becomes reachable without authentication after a security-config change
(default-deny erosion). Ground, against the live code, everything a security-slice MockMvc test
needs to prove that **every non-permit-listed route returns 401 to an anonymous caller**, that the
**permit-list is exactly the bootstrap set**, that a **newly added route defaults to authenticated**,
and that the **entry point is 401, not a redirect** — asserting *behavior per route* (a permit-list
typo must fail a test, not compile cleanly), while avoiding the anti-patterns of testing one endpoint
and generalizing, or snapshotting the security config instead of exercising routes.

## Summary

The auth boundary is a **single explicit `SecurityFilterChain`** (`SecurityConfig.java:31-52`) with
three properties the test must lock:

1. **Default-deny** — the chain ends in `.anyRequest().authenticated()` (`SecurityConfig.java:42`).
2. **401, not a redirect** — the entry point is `HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)`
   (`SecurityConfig.java:43-44`); there is no `formLogin`/`httpBasic`, so an unauthenticated API call
   returns a bare 401 rather than a 302 to a login page.
3. **A 4-entry permit-list** (`SecurityConfig.java:39-41`): `OPTIONS /**`, `GET /api/auth/csrf`,
   `POST /api/auth/register`, `POST /api/auth/login`.

Only **two `@RestController`s** exist. Three protected app routes are live today
(`GET /api/auth/me`, `POST /api/analyses`, `GET /api/analyses/{id}`) — and **two of them were secured
with zero edits to the security config**, purely by inheriting default-deny (confirmed in the archived
`analyze-and-save-contract` change and by `git diff`). That inheritance is load-bearing and currently
**unguarded** — exactly the Risk #5 gap.

The cheapest protection is an **anonymous → 401 route matrix**, but two mechanics of the current config
change what "→ 401" means per method, and the test must encode both or it will bake in a false
assertion:

- **CSRF (`csrf.spa()`, `SecurityConfig.java:36`) fires before authorization.** An anonymous **POST
  without a CSRF token → 403 (CSRF), not 401.** To reach the *authentication* boundary on a
  state-changing verb, the matrix row **must send `.with(csrf())`** — otherwise it silently asserts
  CSRF, not auth. GET rows are CSRF-exempt and go straight to 401.
- **`POST /api/auth/logout` returns 204 (or 403 without a token), never 401** — `LogoutFilter` runs
  before `AuthorizationFilter`, so logout executes with no auth gate. It is a non-permit-listed route
  that breaks the naive "non-permit-listed ⇒ 401" rule and must be special-cased.

Two further findings sharpen the test:

- **Unmapped paths return 401, not 404** (`AuthorizationFilter` runs before `DispatcherServlet`
  routing). This is the *strong* way to encode "a newly added route defaults to authenticated": probe
  an arbitrary path that no handler serves and assert 401 — it fails the moment default-deny is
  weakened to `permitAll` at the wrong altitude.
- **Actuator is on the classpath** (`spring-boot-starter-actuator`, `pom.xml:36`), so `/actuator/health`
  is a real framework endpoint that also falls to default-deny → 401. Including one actuator row proves
  the guarantee extends beyond app controllers. springdoc/swagger and H2 console are **absent**.

There is already **one** anonymous→401 test — `AuthControllerTest.anonymousMeReturns401`
(`AuthControllerTest.java:39`). That single-endpoint assertion *is* the "test one and generalize"
anti-pattern the change warns against; Phase 1's job is to generalize it into a full matrix plus a
permit-list guard.

## Detailed Findings

### The security configuration (the contract under test)

`backend/src/main/java/com/morawski/dev/falcon/auth/SecurityConfig.java`

- `.cors(withDefaults())` (`:35`) — CORS source at `:72-82`, allowing origin `http://localhost:3000`,
  methods **`GET, POST` only** (`:75`), header `X-XSRF-TOKEN`, credentials on.
- `.csrf(csrf -> csrf.spa())` (`:36`) — Spring Security 7 SPA CSRF shortcut (JS-readable `XSRF-TOKEN`
  cookie + `X-XSRF-TOKEN` header). **CSRF stays enforced on POST/PUT/PATCH/DELETE.**
- `.securityContext(...)` (`:37`) with `HttpSessionSecurityContextRepository` (`:66-69`) — session-based.
- **Permit-list** (`.authorizeHttpRequests`, `:38-42`):
  - `OPTIONS /**` → `permitAll` (`:39`) — CORS preflight infrastructure.
  - `GET /api/auth/csrf` → `permitAll` (`:40`).
  - `POST /api/auth/register`, `POST /api/auth/login` → `permitAll` (`:41`).
  - `.anyRequest().authenticated()` (`:42`) — **default-deny**.
- **Entry point** `HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)` (`:43-44`) — **401, not a redirect**.
- `.logout(...)` (`:45-49`): `logoutUrl("/api/auth/logout")`, success handler sets **204**, invalidates
  session, deletes `JSESSIONID`. **Not in the authorization permit-list.**
- Comment at `:50`: "no formLogin / httpBasic" — this absence is *why* the entry point governs the
  status; a regression that re-introduces `formLogin` (or drops the explicit chain, falling back to
  Boot defaults) would turn 401s into 302 redirects. The matrix's "401 not redirect" assertion catches
  exactly that.

### Complete route inventory (the matrix rows)

Two `@RestController`s; two `@RestControllerAdvice`es contribute **no paths** (they map exception types,
not URLs). No `@Controller`, no SPA-forward controller, no static/webjars content, no springdoc.

| Route | Method | File:line | Permit-listed? | Anonymous status |
|-------|--------|-----------|----------------|------------------|
| `/api/auth/csrf` | GET | `AuthController.java:49` | **yes** | 204 (reaches controller) |
| `/api/auth/register` | POST | `AuthController.java:56` | **yes** (needs csrf token) | 201/400/409 (reaches controller) |
| `/api/auth/login` | POST | `AuthController.java:67` | **yes** (needs csrf token) | 200/401-bad-creds (reaches controller) |
| `/api/auth/me` | GET | `AuthController.java:85` | no | **401** ← already tested |
| `/api/analyses` | POST | `AnalysisController.java:27` | no | **401** (with csrf) / 403 (without) |
| `/api/analyses/{id}` | GET | `AnalysisController.java:34` | no | **401** |
| `/api/auth/logout` | POST | `SecurityConfig.java:45-49` | no | **204** (with csrf) / 403 (without) ← rule-breaker |
| `/actuator/health` (+ `/actuator`) | GET | framework (`pom.xml:36`) | no | **401** |
| `/error` (direct hit) | GET | framework (`spring-boot-starter-webmvc`, `pom.xml:56`) | no | **401** |
| any unmapped path, e.g. `/api/does-not-exist` | GET/POST | n/a | no | **401** (not 404) |

Class prefixes: `AuthController` → `/api/auth` (`AuthController.java:33`); `AnalysisController` →
`/api/analyses` (`AnalysisController.java:18`).

### Per-method behavior (the assertion contract — get this exactly right)

Filter order (Spring Security 7): `CorsFilter` → **`CsrfFilter`** → **`LogoutFilter`** → …auth… →
`AnonymousAuthenticationFilter` → `ExceptionTranslationFilter` → **`AuthorizationFilter`**. CSRF and
logout are evaluated *before* authorization; the 401 entry point fires only when `AuthorizationFilter`
throws `AccessDeniedException` for an anonymous (unauthenticated) principal.

| Method + path class (anonymous) | `.with(csrf())`? | Expected status | Why |
|---|---|---|---|
| GET protected (`/api/auth/me`, `/api/analyses/{id}`) | no | **401** | CSRF-exempt → authorization → entry point |
| POST protected (`/api/analyses`) | **yes** | **401** | csrf passes → auth fails → entry point |
| POST protected | **no** | **403** | `CsrfFilter.sendError(403)` *before* auth — asserts CSRF, not auth |
| GET/POST **unmapped** path | (yes for POST) | **401** | default-deny; filter runs before dispatch, so not 404 |
| `GET /actuator/health` | no | **401** | not permit-listed → default-deny |
| `OPTIONS` any path | n/a | **not a 401 row** | permitAll + CORS infra — exclude (see Open Questions) |
| `POST /api/auth/logout` | **yes** | **204** | `LogoutFilter` before auth, no auth gate; success handler → 204 |
| `POST /api/auth/logout` | **no** | **403** | `CsrfFilter` blocks before `LogoutFilter` |

- **401 uses `setStatus()`, not `sendError()`** → no ERROR re-dispatch; **MockMvc status == real-server
  status** for every row. Only response *bodies* may differ (real server may render Boot's error JSON;
  MockMvc leaves it empty) — so **assert status codes only, never bodies**.
- Central rule for the plan: **every state-changing verb needs `.with(csrf())` to test the auth (401)
  boundary**; drop it only when deliberately asserting the CSRF (403) boundary. Anonymous = simply omit
  `.with(user(...))`.

### Existing test infrastructure (the pattern to mirror)

- **Slice choice.** Every existing HTTP test uses full-context `@SpringBootTest` +
  `@AutoConfigureMockMvc` + `@Import(TestcontainersConfiguration.class)`, **not** `@WebMvcTest`
  (`AuthFlowTest.java:30-33`, `AuthControllerTest.java:22-25`, `AnalysisFlowTest.java:45-48`). Reason
  (per test-plan §6.2 and the archived plan): a bare slice / `@DataJpaTest` fails — there is no embedded
  DB on the classpath; the real Postgres comes from Testcontainers (`TestcontainersConfiguration.java:9-17`,
  `postgres:18` via `@ServiceConnection`).
- **Import path is the Boot 4 location**: `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`
  (`AuthFlowTest.java:14`) — not the old `...boot.test.autoconfigure.web.servlet`.
- **Post-processors**: `SecurityMockMvcRequestPostProcessors.csrf()` and `.user(principal)`
  (`AuthFlowTest.java:24`, `AnalysisFlowTest.java:31-32`). Anonymous requests just omit `.with(user(...))`.
- **Reference for the exact shape being generalized**: `AuthControllerTest.anonymousMeReturns401`
  (`AuthControllerTest.java:38-42`) — `mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized())`.
- **Cleanup note**: existing tests clean up persisted rows in `@AfterEach`. The anon→401 matrix creates
  **no data** (anonymous requests are rejected before touching the DB), so it needs no DB cleanup — a
  useful property that argues for a *dedicated* test class rather than bolting rows onto a data-touching
  flow test.

### The permit-list guard (making a typo fail a test, not compile)

"Assert behavior per route" means: for each of the four permit-listed matchers, an anonymous request
must reach the controller (a **non-401** status), and for the closed set of protected routes an
anonymous request must be 401/403/204 as tabulated. A permit-list typo (e.g. `/api/auth/registre`) then
surfaces as a live status mismatch. Note the tension the plan must resolve: the change's stated
permit-list is *3* routes ("register, login, csrf"), but the config has *4* matchers (the extra is
`OPTIONS /**`). The guard must decide whether OPTIONS is "in the permit-list contract" or "CORS
infrastructure outside it" (see Open Questions).

## Code References

- `backend/src/main/java/com/morawski/dev/falcon/auth/SecurityConfig.java:36` — `csrf.spa()` (enforced on POST)
- `backend/src/main/java/com/morawski/dev/falcon/auth/SecurityConfig.java:39-41` — permit-list (OPTIONS, csrf, register, login)
- `backend/src/main/java/com/morawski/dev/falcon/auth/SecurityConfig.java:42` — `.anyRequest().authenticated()` (default-deny)
- `backend/src/main/java/com/morawski/dev/falcon/auth/SecurityConfig.java:43-44` — `HttpStatusEntryPoint(UNAUTHORIZED)` (401, not redirect)
- `backend/src/main/java/com/morawski/dev/falcon/auth/SecurityConfig.java:45-49` — logout (204, not permit-listed)
- `backend/src/main/java/com/morawski/dev/falcon/auth/SecurityConfig.java:75` — CORS methods `GET, POST` only
- `backend/src/main/java/com/morawski/dev/falcon/auth/AuthController.java:49,56,67,85` — csrf / register / login / me
- `backend/src/main/java/com/morawski/dev/falcon/analysis/AnalysisController.java:18,27,34` — prefix / create / get
- `backend/src/test/java/com/morawski/dev/falcon/auth/AuthControllerTest.java:38-42` — the single existing anon→401 test to generalize
- `backend/src/test/java/com/morawski/dev/falcon/auth/AuthFlowTest.java:24,30-33,77-78` — MockMvc pattern, csrf import, logout→204
- `backend/src/test/java/com/morawski/dev/falcon/analysis/AnalysisFlowTest.java:31-32,121` — `.with(csrf()).with(user(...))` on a protected POST
- `backend/src/test/java/com/morawski/dev/falcon/TestcontainersConfiguration.java:9-17` — Postgres 18 via `@ServiceConnection`
- `backend/pom.xml:36` — `spring-boot-starter-actuator` (→ `/actuator/health` default-denied)
- `backend/pom.xml:56` — `spring-boot-starter-webmvc` (→ `/error` mapping)

## Architecture Insights

- **Default-deny is inherited, and the inheritance is the product.** New controllers get protected for
  free by `.anyRequest().authenticated()`. That is elegant, but it means the security posture of every
  future route is a property of *one* line in *one* file, with no per-route declaration to review. A
  behavior matrix (not a config snapshot) is the only artifact that fails when that single line erodes.
- **The entry point is the real contract, more than the permit-list.** The permit-list is 4 explicit
  matchers a reviewer can eyeball; the "401 not 302" guarantee is an emergent property of *absent*
  `formLogin`/`httpBasic` plus an explicit `HttpStatusEntryPoint`. Emergent guarantees are the ones that
  regress silently — assert it directly.
- **CSRF is an ordering hazard for auth tests.** Because `CsrfFilter` precedes `AuthorizationFilter`, a
  naively-written "anonymous POST should be 401" test actually observes 403 and either fails
  confusingly or (if it only asserts "not 200") passes while testing the wrong boundary. The `.with(csrf())`
  discipline the codebase already follows on every POST is the tell.
- **The strongest default-deny assertion targets a route that doesn't exist.** Since unmapped paths 401
  (filter before dispatch), a single probe of a bogus path encodes "anything not explicitly opened is
  closed" — which is a truer statement of the invariant than enumerating today's known routes.

## Historical Context (from prior changes)

- **`context/archive/2026-07-04-identity-and-isolation/`** built the entire boundary (F-01). Its plan is
  explicit about the decisions the matrix now locks:
  - `plan.md:51` — "API auth entry point must be 401, not a redirect… `HttpStatusEntryPoint(UNAUTHORIZED)`
    so unauthenticated API calls return 401 instead of Spring's default redirect." (The exact contract.)
  - `plan.md:132,139-143` — the permit-list + `.anyRequest().authenticated()` + entry-point wiring as
    designed.
  - `plan-brief.md:21` — the boundary was *designed to be inherited*: "Prove the CORS/session/CSRF loop
    now so **S-01 inherits a working auth boundary**."
  - `plan.md:54` — self-flagged uncertainty: "Spring Security 7 (Boot 4.0) is **very new**… minor API
    drift is possible; verify against the actual dependency." → argues for exercising behavior, not
    trusting the config's intended semantics.
- **`context/archive/2026-07-06-analyze-and-save-contract/`** (S-01) is the live proof of the risk: it
  added `AnalysisController`'s two routes and **relied on auto-gating with no security-config change**:
  - `research.md:119` — "Auto-gating — `SecurityConfig.java:38-42` ends with `.anyRequest().authenticated()`,
    so new `/api/analyses/**` endpoints require a session with **no config change**."
  - `plan.md:230` — "Both auto-gated by F-01's `.anyRequest().authenticated()`."
  - `reviews/impl-review.md:86` — "`SecurityConfig` untouched, `/api/analyses/**` auto-gated by the
    existing `.anyRequest().authenticated()`."
  - `research.md:125` / `plan.md:36` — flagged forward: CORS allows only `GET, POST` (`SecurityConfig.java:75`);
    **S-02 (PATCH/PUT) and S-04 (DELETE) will need the CORS method list extended** — and, by the same
    token, will add protected routes the matrix must grow to cover.
- **Churn (git).** `SecurityConfig.java` has exactly **one** commit (`713c794`, identity-and-isolation p2);
  the `auth/` directory has **three** (`713c794`, `4c0fd9f` auth API, `989fd68` 409-on-race). The
  test-plan's "6 commits/30d" estimate over-counts, but the direction holds: the auth slice is the only
  domain code with history, and `SecurityConfig` is a single-point-of-failure file. `git diff` confirms
  none of the five `analyze-and-save-contract` commits touched any `auth/` file.

## Related Research

- `context/archive/2026-07-06-analyze-and-save-contract/research.md` — S-01's codebase research (auth
  reuse, auto-gating, owner-scoping primitive, test patterns).
- `context/archive/2026-07-04-identity-and-isolation/plan.md` — F-01's security-config design and the
  "401 not redirect" decision.
- `context/foundation/test-plan.md` §2 (Risk #5 row, line 57), §2 Risk Response (line 84), §6.3 (the
  TBD auth-boundary cookbook this phase fills in).

## Open Questions

These are design forks for `/10x-plan` to resolve — the research surfaces them; it does not pre-decide.

1. **Does `OPTIONS /**` count as "the permit-list"?** The change says the permit-list is the 3 bootstrap
   routes; the config has a 4th matcher (`OPTIONS /**`) that is CORS infrastructure. Recommendation:
   exclude OPTIONS from the per-route 401 matrix (it is never 401), and either (a) treat the permit-list
   contract as the 3 app routes only, or (b) add one dedicated CORS-preflight test (`Origin` +
   `Access-Control-Request-Method` headers → 200 + `Access-Control-Allow-Origin`). Don't assert plain
   MockMvc `options(path)` — its status is handler-mapping-driven, not security-driven (weak signal).
2. **How is "logout returns 204, not 401" expressed?** It's a genuine exception to the matrix. Options:
   assert it explicitly (`POST /api/auth/logout` + csrf → 204) as a documented carve-out, or scope the
   matrix to controller-mapped protected routes and cover logout in the existing `AuthFlowTest`. Either
   way the plan must name it so a future reader doesn't "fix" the matrix into a false 401.
3. **How enforceable is "a newly added route defaults to authenticated"?** Two grades: (a) an
   unmapped-path probe (`GET /api/__does_not_exist__` → 401) — cheap, encodes the invariant, but doesn't
   fail when *someone adds a real route and opens it*; (b) reflect over `RequestMappingHandlerMapping` to
   enumerate mapped routes and assert each non-bootstrap one is 401 — stronger (a newly-added route is
   automatically covered), but more machinery. Pick per cost×signal; (a) + the explicit matrix is likely
   the MVP-right answer.
4. **Include framework endpoints (`/actuator/health`, `/error`) as rows?** They prove default-deny
   extends past app controllers. Low cost, real signal — recommend including at least `/actuator/health`.
5. **Coupling to future slices.** When S-02/S-04 add PATCH/DELETE routes, both the matrix *and* the CORS
   method list (`SecurityConfig.java:75`) must grow. Worth a one-line note in the test (or cookbook §6.3)
   so the coupling is discoverable.

## Metadata note

GitHub permalinks were intentionally omitted: on `main` at commit `2392325`, but push state of this
commit to `origin` was not verified, so local `file:line` references (clickable in the terminal) are
used to avoid emitting links that could 404.

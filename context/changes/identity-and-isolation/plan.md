# Identity & Per-User Isolation (F-01) Implementation Plan

## Overview

Falcon's north star (S-01: analyze & save a pasted contract) requires "a logged-in user" and owner-scoped persistence. F-01 builds that gate: open self-serve registration, form/session login over a JSON API, an in-memory HTTP session, and — most importantly — the **reusable per-user isolation primitive** that every later slice (S-01–S-04) inherits. Contracts are sensitive data, so isolation is a query-level domain invariant, not a UI nicety.

Scope is deliberately minimal per the roadmap: `User` entity + first migration, security config, register/login/logout/me, a thin Next.js auth surface, and the `SecurityUtils.currentUserId()` helper. No roles, no admin, no password reset — those are explicit non-goals.

## Current State Analysis

The backend is "all plumbing, zero domain":

- **Security is already implicitly on.** `spring-boot-starter-security` is on the classpath (`backend/pom.xml:47`), so Spring Security's default filter chain currently locks every endpoint behind HTTP Basic with a generated password. F-01 *replaces* this implicit default with an explicit `SecurityFilterChain` bean — there is no existing `SecurityConfig`, `User`, or controller to work around.
- **Empty schema.** `backend/src/main/resources/db/changelog/db.changelog-master.yaml:9` is `databaseChangeLog: []` with a documented convention: add each change under `db/changelog/changes/` and wire it via `include`. Zero entities exist.
- **Test infra is ready.** `TestcontainersConfiguration` (`backend/src/test/java/.../TestcontainersConfiguration.java`) provides a Postgres 18 container via `@ServiceConnection`; `spring-boot-starter-security-test`, `-data-jpa-test`, `-webmvc-test` are all on the classpath (`pom.xml:91-115`). The one existing test is `FalconApplicationTests.contextLoads` — it must stay green.
- **Base package** is `com.morawski.dev.falcon` (groupId `com.morawski.dev`, artifact `falcon`).
- **Frontend** is a bare Next.js 16.2.10 / React 19.2.4 scaffold (`frontend/src/app/{layout,page}.tsx`): shadcn/ui + `radix-ui` configured, Tailwind 4, `lucide-react`. **No form library, no auth library, no state manager.** UI language is Polish.

### Key Discoveries:

- **Spring Security 7 ships a one-line SPA CSRF shortcut:** `http.csrf(csrf -> csrf.spa())` wires the full single-page-app pattern (JS-readable `XSRF-TOKEN` cookie + `X-XSRF-TOKEN` header + BREACH handling). Verified in the Security 7 reference — prefer it over hand-assembling `CookieCsrfTokenRepository` + a request handler.
- **`AuthenticationManager` is a published bean** = `ProviderManager(new DaoAuthenticationProvider(userDetailsService))` with `setPasswordEncoder(...)`; `PasswordEncoder` = `PasswordEncoderFactories.createDelegatingPasswordEncoder()` (BCrypt default). Confirmed against Security 7 docs.
- **`localhost:3000` → `localhost:8080` is cross-origin but same-site.** Origin = scheme+host+**port**, so CORS applies (need `Access-Control-Allow-Credentials` + specific origin + `credentials:'include'`). But *site* = registrable domain (port-independent), so `SameSite=Lax` session cookies are still sent. No `SameSite=None; Secure` needed in local dev; production cross-domain would.
- The only owner-scoped entity, `Analysis`, is **S-01's**, not F-01's. So F-01 proves the *auth boundary* (anon → 401, authenticated → own identity) and ships the isolation primitive; the cross-user *data* isolation test lands in S-01 when there's a second user's data to isolate.

## Desired End State

A new visitor can register (email + password), log in from the browser, and land on a protected placeholder page that greets them by email; logging out or visiting a gated route while unauthenticated redirects to `/login`. On the backend, `GET /api/auth/me` returns the caller's identity and returns `401` to anonymous callers, and any service can obtain the current user's id via `SecurityUtils.currentUserId()` — the primitive S-01's owner-scoped queries will be built on. The full backend flow (register → login → me → logout, plus duplicate-email/bad-creds/validation errors) is covered by deterministic MockMvc tests; the cross-origin session + XSRF loop is verified manually in the browser.

**Verify:** `cd backend && ./mvnw clean package` is green (incl. new auth tests); `cd frontend && pnpm build && pnpm lint` pass; a manual browser walk of register → login → protected page → logout succeeds against a locally-running backend.

## What We're NOT Doing

- **No `Analysis` entity or cross-user data isolation test** — that's S-01. F-01 stops at the auth boundary + the reusable primitive.
- **No roles / admin / guest tiers** — flat single-role model (explicit PRD non-goal).
- **No password reset, email verification, "remember me", or social/OAuth login** — email+password only.
- **No JWT / token auth** — session cookie only (roadmap pre-decided form/session).
- **No Spring Session / persistent session store** — in-memory session (single-instance MVP).
- **No frontend E2E (Playwright) test** — the frontend loop is verified manually.
- **No production CORS/domains, HTTPS hardening, or rate limiting** — local dev config only; production is above-MVP.
- **No `docs/` or S-01 domain screens** — the protected page is a throwaway placeholder S-01 replaces.

## Implementation Approach

Vertical build, bottom-up, so each layer is green before the next depends on it: **data → security config → auth API → frontend**. The backend phases (1–3) are fully covered by fast, deterministic MockMvc + Testcontainers tests using the `spring-security-test` starter already on the classpath. The frontend phase (4) closes the loop and is verified manually in the browser (per the chosen test strategy). Isolation is enforced by the **explicit owner-filter convention**: repository methods that return owned data take an `ownerId` parameter, and callers pass `SecurityUtils.currentUserId()` — there is no bare `findById` for owned entities.

## Critical Implementation Details

- **Programmatic login does NOT auto-save the session (Security 6+).** After `authenticationManager.authenticate(...)`, the resulting `SecurityContext` must be explicitly persisted with a `SecurityContextRepository` (`HttpSessionSecurityContextRepository`), or no session cookie is issued and `/me` stays `401`. This is the single most likely bug in Phase 3.
- **CSRF bootstrap ordering.** With `csrf().spa()` the `XSRF-TOKEN` cookie must exist *before* the first POST. The frontend calls `GET /api/auth/csrf` (which forces the token to materialize) to prime the cookie, then echoes it as `X-XSRF-TOKEN` on login/register/logout. After login, Spring rotates the session and refreshes the cookie — the fetch wrapper always reads the *current* cookie, so it self-corrects.
- **API auth entry point must be 401, not a redirect.** Register an `authenticationEntryPoint` of `HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)` so unauthenticated API calls return `401` (the SPA/middleware decides where to send the user) instead of Spring's default redirect to a login page.
- **The principal must carry the user id.** `AppUserDetails` (custom `UserDetails`) wraps the `User` and exposes `getId()`, so `SecurityUtils.currentUserId()` and `@AuthenticationPrincipal` read the id without re-querying by email.
- **Schema is Liquibase-owned.** Set `spring.jpa.hibernate.ddl-auto=validate` so Hibernate checks the entity against the migration instead of mutating the schema. Table name is `users` (`user` is a reserved word in Postgres). Normalize email to lowercase before persist/lookup to make uniqueness case-insensitive.

## Phase 1: User entity & first migration

### Overview

Create the `User` aggregate, its Liquibase migration, and the repository — the data foundation everything else hangs off.

### Changes Required:

#### 1. User entity

**File**: `backend/src/main/java/com/morawski/dev/falcon/user/User.java`

**Intent**: The account record and basis for access control (PRD §5). Persisted via JPA; schema is owned by Liquibase, so the entity only annotates, it does not generate DDL.

**Contract**: JPA `@Entity @Table(name = "users")`. Fields: `Long id` (`@Id @GeneratedValue(IDENTITY)`), `String email` (unique, not null), `String passwordHash` (not null), `Instant createdAt` (not null). Expose `getId()`/`getEmail()` for the principal. No relationships yet (`Analysis` links in S-01).

#### 2. First Liquibase migration

**File**: `backend/src/main/resources/db/changelog/changes/001-create-users.yaml` (new) + edit `db/changelog/db.changelog-master.yaml`

**Intent**: Create the `users` table and wire the change into the (currently empty) master changelog via `include`.

**Contract**: `createTable name: users` with columns `id` (bigint, PK, autoincrement), `email` (varchar, `nullable:false` + unique constraint), `password_hash` (varchar, not null), `created_at` (`timestamptz`/`java.sql.Timestamp`, not null). Master changelog `databaseChangeLog:` gets one `- include: { file: db/changelog/changes/001-create-users.yaml }`.

#### 3. Repository

**File**: `backend/src/main/java/com/morawski/dev/falcon/user/UserRepository.java`

**Intent**: Data access for users; the lookup key is email (used by login/registration).

**Contract**: `interface UserRepository extends JpaRepository<User, Long>` with `Optional<User> findByEmail(String email)` and `boolean existsByEmail(String email)`.

#### 4. JPA config

**File**: `backend/src/main/resources/application.properties`

**Intent**: Let Liquibase own the schema and validate the entity against it.

**Contract**: add `spring.jpa.hibernate.ddl-auto=validate` (and optionally `spring.jpa.open-in-view=false`).

### Success Criteria:

#### Automated Verification:

- Context loads with the migration applied: `cd backend && ./mvnw test -Dtest=FalconApplicationTests`
- Repository test passes (save, `findByEmail`, duplicate-email violation): `./mvnw test -Dtest=UserRepositoryTest`

#### Manual Verification:

- After `./mvnw spring-boot:run` (see **Local-run prerequisites** under Testing Strategy — Postgres up + a dummy `OPENROUTER_API_KEY` set), the `users` table exists with the expected columns (inspect via psql or the Liquibase startup log).

**Implementation Note**: After automated verification passes, pause for manual confirmation before Phase 2. Phase bullets are tracked as checkboxes in `## Progress`.

---

## Phase 2: Security config & isolation primitive

### Overview

Replace the implicit default filter chain with an explicit, SPA-friendly `SecurityFilterChain`, publish the auth beans, expose a protected `GET /api/auth/me`, and ship the `SecurityUtils.currentUserId()` primitive.

### Changes Required:

#### 1. Custom UserDetails + service

**File**: `backend/src/main/java/com/morawski/dev/falcon/user/AppUserDetails.java`, `AppUserDetailsService.java`

**Intent**: Bridge the `User` entity to Spring Security so the authenticated principal carries the user id (not just the username).

**Contract**: `AppUserDetails implements UserDetails` wrapping a `User`, exposing `getId()`, `getUsername()`=email, `getPassword()`=passwordHash, a single hardcoded authority (e.g. `ROLE_USER`). `AppUserDetailsService implements UserDetailsService` → `loadUserByUsername(email)` looks up via `UserRepository.findByEmail` (lowercased), throwing `UsernameNotFoundException` if absent.

#### 2. Security configuration

**File**: `backend/src/main/java/com/morawski/dev/falcon/auth/SecurityConfig.java`

**Intent**: The one explicit filter chain for the JSON API — SPA CSRF, CORS for the Next.js origin, in-memory session, 401 entry point, and the permit-list for the auth bootstrap endpoints.

**Contract**: `@Configuration @EnableWebSecurity`, beans: `SecurityFilterChain`, `PasswordEncoder`, `AuthenticationManager`, `SecurityContextRepository` (`HttpSessionSecurityContextRepository`), `CorsConfigurationSource`. The chain permits `POST /api/auth/register`, `POST /api/auth/login`, `GET /api/auth/csrf`; all else `authenticated()`. Non-obvious wiring (signature contract Phase 3 depends on):

```java
http
  .cors(withDefaults())
  .csrf(csrf -> csrf.spa())                                  // Security 7 SPA shortcut
  .authorizeHttpRequests(auth -> auth
      .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
      .requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
      .anyRequest().authenticated())
  .exceptionHandling(e -> e
      .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
  .logout(l -> l.logoutUrl("/api/auth/logout")
      .logoutSuccessHandler((req, res, a) -> res.setStatus(204))
      .invalidateHttpSession(true).deleteCookies("JSESSIONID"));
// no formLogin / httpBasic — login is the custom JSON endpoint
```

`CorsConfigurationSource` allows origin `http://localhost:3000`, `allowCredentials=true`, methods GET/POST, headers incl. `Content-Type` and `X-XSRF-TOKEN`.

#### 3. Isolation primitive

**File**: `backend/src/main/java/com/morawski/dev/falcon/auth/SecurityUtils.java`

**Intent**: The single, reused way to answer "who is the current user?" for owner-scoped queries — the load-bearing contract S-01–S-04 inherit.

**Contract**: `static Long currentUserId()` reads `SecurityContextHolder.getContext().getAuthentication().getPrincipal()`, casts to `AppUserDetails`, returns `getId()`; throws (or returns a clear error) if unauthenticated. Document the convention alongside it: *every repository method returning owned data takes an `ownerId` (e.g. `findByIdAndOwnerId`); callers pass `SecurityUtils.currentUserId()`; there is no bare `findById` for owned entities.* **Ship a focused unit test** (`SecurityUtilsTest`) — no F-01 endpoint exercises this helper directly (`/me` reads `@AuthenticationPrincipal`), so the primitive S-01–S-04 inherit would otherwise ship unverified.

#### 4. Protected identity endpoint

**File**: `backend/src/main/java/com/morawski/dev/falcon/auth/AuthController.java`, `dto/UserResponse.java`

**Intent**: A minimal protected endpoint to prove the auth boundary now; extended with register/login in Phase 3.

**Contract**: `GET /api/auth/me` → `UserResponse(Long id, String email)` read from `@AuthenticationPrincipal AppUserDetails`. `UserResponse` is a record.

### Success Criteria:

#### Automated Verification:

- Full build + suite green: `cd backend && ./mvnw clean package`
- Anonymous `GET /api/auth/me` → `401` (MockMvc test)
- Authenticated `GET /api/auth/me` (via `SecurityMockMvcRequestPostProcessors.user(appUserDetails)`) → `200` with the caller's id + email
- `SecurityUtils.currentUserId()` unit test passes (returns the id from a populated `SecurityContextHolder`; throws when the context is anonymous)

#### Manual Verification:

- App starts with the explicit chain (no generated default password in logs).
- A CORS preflight (`OPTIONS`) from `http://localhost:3000` to `/api/auth/me` returns the expected `Access-Control-Allow-*` headers.

**Implementation Note**: Pause for manual confirmation before Phase 3.

---

## Phase 3: Auth API (register / login / logout)

### Overview

Complete the JSON auth surface: registration, the session-persisting login, the CSRF-priming endpoint, and error semantics — all covered by a deterministic MockMvc flow test.

### Changes Required:

#### 1. Request DTOs with validation

**File**: `backend/src/main/java/com/morawski/dev/falcon/auth/dto/RegisterRequest.java`, `LoginRequest.java`

**Intent**: Typed, validated request bodies (Jakarta Validation is already on the classpath).

**Contract**: `RegisterRequest(@Email @NotBlank String email, @NotBlank @Size(min=8) String password)`; `LoginRequest(@NotBlank String email, @NotBlank String password)`. Both records.

#### 2. Registration

**File**: `AuthController.java` (+ a `UserService` or inline)

**Intent**: Open self-serve sign-up: validate, reject duplicate email, hash the password, persist.

**Contract**: `POST /api/auth/register` — normalize email to lowercase; if `existsByEmail` → `409 Conflict`; else save `User` with `passwordEncoder.encode(password)` and `createdAt=now`; return `201 Created` with `UserResponse`. Does **not** start a session (the frontend performs a follow-up login).

#### 3. Session-persisting login

**File**: `AuthController.java`

**Intent**: Authenticate credentials and persist the security context to the HTTP session (the Phase-3 gotcha).

**Contract**: `POST /api/auth/login` — build `UsernamePasswordAuthenticationToken(email, password)`, call `authenticationManager.authenticate(...)`; on success create an empty `SecurityContext`, set the authentication, `SecurityContextHolder.setContext(context)`, and **`securityContextRepository.saveContext(context, request, response)`**; return `200` with `UserResponse`. A bad credential throws `AuthenticationException` → mapped to `401` (via `@ExceptionHandler` or the entry point).

#### 4. CSRF priming + logout

**File**: `AuthController.java` (csrf); logout handled by the Phase-2 `.logout()` DSL

**Intent**: Let the SPA obtain the CSRF cookie before its first mutation; logout clears the session.

**Contract**: `GET /api/auth/csrf` takes a `CsrfToken` param, touches `getToken()` to force the cookie write, returns `204`. Logout is `POST /api/auth/logout` (configured in Phase 2) → `204` + invalidated session.

#### 5. Error mapping

**File**: `backend/src/main/java/com/morawski/dev/falcon/auth/AuthExceptionHandler.java` (or `@RestControllerAdvice`)

**Intent**: Consistent JSON error responses.

**Contract**: `AuthenticationException` → `401`; duplicate email → `409`; `MethodArgumentNotValidException` → `400`. Bodies are small JSON (`{ "error": "..." }`); do not leak whether an email exists on login failure.

### Success Criteria:

#### Automated Verification:

- Full flow MockMvc test (using `.with(csrf())`): register `201` → login `200` (+ session) → me `200` → logout `204`: `./mvnw test -Dtest=AuthFlowTest`
- Error cases: duplicate email → `409`, bad credentials → `401`, invalid payload (`bad email` / short password) → `400`
- Whole suite green: `cd backend && ./mvnw clean package`

#### Manual Verification:

- (Optional) A REST client / curl walk of csrf → register → login → me → logout succeeds with a real session cookie + `X-XSRF-TOKEN` header.

**Implementation Note**: Pause for manual confirmation before Phase 4.

---

## Phase 4: Frontend thin auth

### Overview

The browser-facing loop: login + register pages, a protected placeholder, middleware redirect, and a `fetch` wrapper that carries credentials + the XSRF header cross-origin. UI copy is Polish.

### Changes Required:

#### 1. shadcn primitives + env

**File**: `frontend/src/components/ui/*` (via CLI), `frontend/.env.local`

**Intent**: Pull the minimal UI primitives and configure the backend base URL.

**Contract**: `pnpm dlx shadcn@latest add button input label card`. `.env.local`: `NEXT_PUBLIC_API_BASE_URL=http://localhost:8080` (documented; not committed with secrets).

#### 2. API + auth client

**File**: `frontend/src/lib/api.ts`, `frontend/src/lib/auth.ts`

**Intent**: One fetch wrapper that every call goes through, handling credentials and the XSRF cookie→header echo; thin auth functions on top.

**Contract**: `apiFetch(path, init)` prefixes `NEXT_PUBLIC_API_BASE_URL`, sets `credentials: 'include'` and `Content-Type: application/json`, and for non-GET reads the `XSRF-TOKEN` cookie and sets `X-XSRF-TOKEN`. `auth.ts` exposes `getCsrf()`, `register()`, `login()`, `logout()`, `me()`. Non-obvious bit — the cookie→header echo:

```ts
function xsrf(): Record<string, string> {
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return m ? { "X-XSRF-TOKEN": decodeURIComponent(m[1]) } : {};
}
// login/register/logout: await getCsrf() first so the cookie exists, then POST with xsrf() headers
```

#### 3. Auth pages + protected placeholder

**File**: `frontend/src/app/(auth)/login/page.tsx`, `(auth)/register/page.tsx`, `frontend/src/app/dashboard/page.tsx`

**Intent**: Minimal Polish-language forms (controlled inputs + `fetch`, no form lib) and a placeholder S-01 will replace.

**Contract**: Login/register are client components with email+password fields, submit via `auth.ts`, surface server errors (409/401/400) inline. Register → on success call `login()` then redirect to `/dashboard`. `/dashboard` calls `me()` and renders "Zalogowano jako {email}" + a "Wyloguj" (logout) button. Labels in Polish (`Zaloguj się`, `Zarejestruj się`, `Email`, `Hasło`).

#### 4. Route protection

**File**: `frontend/src/middleware.ts`

**Intent**: Redirect unauthenticated visitors away from gated routes before render (backend `401` remains the authoritative gate — defense in depth).

**Contract**: `middleware` with `matcher: ['/dashboard/:path*']`; if the `JSESSIONID` cookie is absent, `NextResponse.redirect(new URL('/login', request.url))`.

### Success Criteria:

#### Automated Verification:

- Frontend builds: `cd frontend && pnpm build`
- Lint passes: `cd frontend && pnpm lint`

#### Manual Verification:

- Register a new account in the browser → ends up logged in on `/dashboard`.
- Log out, log back in → `/dashboard` shows the correct email.
- While logged out, navigating directly to `/dashboard` → redirected to `/login` (middleware).
- The full cross-origin session + XSRF loop works `:3000` → `:8080` (no CORS/CSRF errors in the console/network tab).

**Implementation Note**: This is the final phase — after manual confirmation, F-01 unblocks S-01.

---

## Testing Strategy

### Unit / slice tests (backend, deterministic):

- `UserRepositoryTest` — save, `findByEmail`, unique-email violation. **Use the existing `@SpringBootTest @Import(TestcontainersConfiguration.class)` pattern** — a bare `@DataJpaTest` fails at startup because there is no embedded DB on the classpath (it defaults to replacing the DataSource); `@SpringBootTest` tests aren't auto-rolled-back, so clean up test data explicitly.
- `SecurityUtilsTest` — `currentUserId()` returns the principal's id from a populated `SecurityContextHolder`; throws when anonymous. Covers the reused isolation primitive that no F-01 endpoint exercises directly.
- Security-layer MockMvc — anonymous `/api/auth/me` → `401`; authenticated (via `user(appUserDetails)` post-processor) → `200`.
- `AuthFlowTest` — MockMvc register → login → me → logout using `.with(csrf())`; plus `409`/`401`/`400` error cases. No real LLM, no network — matches the PRD's test-determinism guardrail.

### Integration:

- The existing `FalconApplicationTests.contextLoads` stays green with the new config, migration, and beans (test profile's dummy `OPENROUTER_API_KEY` keeps the AI autoconfig booting).

### Manual Testing Steps:

1. **Local-run prerequisites** (needed for every manual step below): (a) **Postgres must be up** — resolve the uncommitted `backend/compose.yaml` → root `docker-compose.yaml` relocation, run `docker compose up` from the repo root, or set `spring.docker.compose.file`, since `spring-boot:run` may no longer auto-discover the compose file from `backend/`; (b) **export a dummy `OPENROUTER_API_KEY`** (any value) — with it unset, `spring-boot:run` fails placeholder resolution at startup (`application.properties` has no default), even though F-01 makes zero LLM calls. Then start the backend (`./mvnw spring-boot:run`) and the frontend (`pnpm dev`).
2. Register → confirm redirect to `/dashboard` with the right email.
3. Log out; confirm `/dashboard` redirects to `/login`.
4. Log in; confirm session persists across a refresh.
5. Inspect the network tab: `login`/`register`/`logout` carry `X-XSRF-TOKEN`; responses set/refresh the session + `XSRF-TOKEN` cookies; no CORS errors.

## Performance Considerations

None material at MVP scale (small users, low QPS). BCrypt hashing cost is per-login/register and negligible at this volume.

## Migration Notes

First-ever migration; no existing data. `001-create-users.yaml` creates the `users` table. `ddl-auto=validate` ensures the entity and migration stay aligned; a mismatch fails fast at startup.

## References

- Roadmap item: `context/foundation/roadmap.md` (F-01, "Identity & per-user isolation")
- Product requirements: `context/foundation/prd.md` (FR-001, Access Control, NFR per-user isolation)
- Domain model: `docs/PRD.md` §5 (`User` fields), §7 (access control)
- Stack: `context/foundation/tech-stack.md`; `backend/pom.xml` (Spring Boot 4.0.7, Spring Security 7, `spring-security-test`)
- Existing test harness: `backend/src/test/java/com/morawski/dev/falcon/TestcontainersConfiguration.java`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: User entity & first migration

#### Automated

- [x] 1.1 Context loads with the migration applied (`./mvnw test -Dtest=FalconApplicationTests`) — fee2cd9
- [x] 1.2 Repository test passes: save, findByEmail, duplicate-email violation — fee2cd9

#### Manual

- [x] 1.3 `users` table exists with expected columns after `spring-boot:run` — fee2cd9

### Phase 2: Security config & isolation primitive

#### Automated

- [x] 2.1 Full build + suite green (`./mvnw clean package`) — 713c794
- [x] 2.2 Anonymous `GET /api/auth/me` → 401 — 713c794
- [x] 2.3 Authenticated `GET /api/auth/me` → 200 with caller id + email — 713c794
- [x] 2.4 `SecurityUtils.currentUserId()` unit test (returns id from a populated context; throws when anonymous) — 713c794

#### Manual

- [x] 2.5 App starts with the explicit chain (no generated default password) — 713c794
- [x] 2.6 CORS preflight from :3000 returns expected headers — 713c794

### Phase 3: Auth API (register / login / logout)

#### Automated

- [x] 3.1 Full flow MockMvc test: register 201 → login 200 → me 200 → logout 204 — 4c0fd9f
- [x] 3.2 Error cases: duplicate email 409, bad credentials 401, invalid payload 400 — 4c0fd9f
- [x] 3.3 Whole suite green (`./mvnw clean package`) — 4c0fd9f

#### Manual

- [x] 3.4 (Optional) curl/REST-client walk of the flow with real session cookie + XSRF header — 4c0fd9f

### Phase 4: Frontend thin auth

#### Automated

- [x] 4.1 Frontend builds (`pnpm build`)
- [x] 4.2 Lint passes (`pnpm lint`)

#### Manual

- [x] 4.3 Register → ends up logged in on `/dashboard`
- [x] 4.4 Log out / log back in → `/dashboard` shows correct email
- [x] 4.5 Direct-navigate to `/dashboard` while logged out → redirected to `/login`
- [x] 4.6 Full cross-origin session + XSRF loop works with no CORS/CSRF console errors

# Identity & Per-User Isolation (F-01) — Plan Brief

> Full plan: `context/changes/identity-and-isolation/plan.md`

## What & Why

Build Falcon's auth gate: open self-serve registration, form/session login over a JSON API, and — the load-bearing part — the reusable per-user isolation primitive every later slice inherits. Contracts are sensitive data, so isolation is a query-level domain invariant, not a UI nicety. F-01 is the one prerequisite for the north star (S-01: analyze & save a pasted contract), which requires "a logged-in user" and owner-scoped persistence.

## Starting Point

Backend is "all plumbing, zero domain": `spring-boot-starter-security` is on the classpath (so the default lock-everything chain is implicitly active), the Liquibase master changelog is empty, and no `User`/`SecurityConfig`/controller exists. The frontend is a bare Next.js 16 scaffold with shadcn/ui configured but no auth, no forms, no state manager. Tests already run against Testcontainers Postgres 18.

## Desired End State

A new visitor can register, log in from the browser, and land on a protected placeholder greeting them by email; logging out or hitting a gated route while unauthenticated redirects to `/login`. `GET /api/auth/me` returns the caller (401 to anonymous), and any service can call `SecurityUtils.currentUserId()` — the primitive S-01's owner-scoped queries build on. Backend flow is covered by deterministic MockMvc tests; the cross-origin session+XSRF loop is verified manually.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Frontend scope | Full-stack thin (login + register + protected placeholder) | Prove the CORS/session/CSRF loop now so S-01 inherits a working auth boundary | Plan |
| Isolation enforcement | Explicit owner filter in queries + `SecurityUtils.currentUserId()` | Greppable, testable, zero framework magic — obvious to copy into S-01–S-04 | Plan |
| Isolation verification in F-01 | Prove auth boundary now; defer cross-user data isolation to S-01 | `Analysis` doesn't exist yet, so tests match what F-01 actually ships | Plan |
| Auth transport | JSON endpoints + `csrf().spa()` cookie-token, session cookie | Clean fetch contract with CSRF kept on, per the roadmap's form/session decision | Plan |
| Sessions | In-memory HTTP session | Zero extra infra; correct for a single-instance MVP | Plan |
| User model | email + BCrypt password hash + createdAt, validated | Matches PRD §5 exactly; minimal and secure-by-default | Plan |
| Test strategy | MockMvc + spring-security-test (+ Testcontainers) | Fast, deterministic auth-boundary proof without a browser | Plan |
| Route protection | Next.js middleware redirect (backend 401 authoritative) | Gate runs before render, no flash of protected content | Plan |

## Scope

**In scope:** `User` entity + first migration; explicit `SecurityFilterChain` (SPA CSRF, CORS, 401 entry point, in-memory session); register/login/logout/me JSON API; `SecurityUtils.currentUserId()` isolation primitive; thin Next.js login/register/dashboard + middleware.

**Out of scope:** `Analysis` entity & cross-user data isolation test (S-01); roles/admin; password reset, email verification, remember-me, OAuth; JWT; persistent sessions; Playwright; production CORS/HTTPS/rate-limiting.

## Architecture / Approach

Vertical, bottom-up build so each layer is green before the next depends on it: **data → security config → auth API → frontend**. Next.js (`:3000`) is a separate app from Spring (`:8080`) — cross-origin (CORS + credentials) but same-site (SameSite=Lax cookies still flow). Isolation is enforced by convention: owned-data queries take an `ownerId`; callers pass `SecurityUtils.currentUserId()`; no bare `findById` for owned entities.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. User entity & migration | `User`, `001-create-users.yaml`, `UserRepository` | Reserved word `user` → table is `users`; keep Liquibase as schema owner |
| 2. Security config & primitive | Explicit `SecurityFilterChain`, auth beans, `/me`, `SecurityUtils` | Getting the SPA CSRF + CORS + 401-entry-point combination right |
| 3. Auth API | register / login / logout / csrf + validation + errors | Session not auto-saved after `authenticate()` — must persist SecurityContext |
| 4. Frontend thin auth | login/register/dashboard pages, middleware, fetch+XSRF wrapper | CSRF cookie must be primed before first POST; cross-origin cookie flow |

**Prerequisites:** local Postgres running (Testcontainers covers tests; local run uses the docker-compose integration or `docker compose up`). Note the uncommitted `compose.yaml` → root `docker-compose.yaml` relocation may need a `spring.docker.compose.file` override for `spring-boot:run`.
**Estimated effort:** ~2–3 after-hours sessions across 4 phases; backend (1–3) is the bulk, frontend (4) is thin.

## Open Risks & Assumptions

- **Spring Security 7 (Boot 4.0) is very new** — `csrf().spa()`, the `AuthenticationManager`/`DaoAuthenticationProvider` wiring, and `HttpStatusEntryPoint` were confirmed against the 7.0 reference, but minor API drift is possible; verify against the actual dependency during Phase 2.
- **Compose relocation** (git-status shows `backend/compose.yaml` deleted, root `docker-compose.yaml` added) may break `spring-boot:run`'s auto-Postgres; doesn't affect tests (Testcontainers). Resolve before manual verification.
- **Local dev cookie flow** relies on `localhost:3000`/`:8080` being same-site; a future production split onto different domains will require `SameSite=None; Secure`.

## Success Criteria (Summary)

- A new user can register and log in from the browser and reach an owner-scoped protected page; logging out / hitting a gated route while unauthenticated redirects to `/login`.
- `GET /api/auth/me` returns the caller and `401` to anonymous callers; `SecurityUtils.currentUserId()` is available for S-01's owner-scoped queries.
- `cd backend && ./mvnw clean package` and `cd frontend && pnpm build && pnpm lint` are all green.

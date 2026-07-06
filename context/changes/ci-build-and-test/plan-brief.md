# CI — Build & Test on Every Push (F-02) — Plan Brief

> Full plan: `context/changes/ci-build-and-test/plan.md`
> Research: `context/changes/ci-build-and-test/research.md`

## What & Why

Roadmap item **F-02**: add GitHub Actions so both apps build and their tests — including
S-01's deterministic, mocked-LLM e2e — run automatically on every push. This is the middle
rung of the stated delivery order (working locally → CI build+tests → production) and the
automated verification path that guards S-01 and the review/history slices layered on top
of it.

## Starting Point

No `.github/` exists — CI is net-new. Both apps already build and test green locally
(`cd backend && ./mvnw clean package`; `cd frontend && pnpm install --frozen-lockfile && pnpm lint && pnpm build`),
with no secrets needed (dummy LLM key in the test profile; mocked `ChatModel`). Two facts
block a clean Linux runner: the frontend has **no toolchain pins** (and the Dockerfile vs
tech-stack disagree on Node/pnpm), and the Maven wrapper is **non-executable in the git
index** (`100644`).

## Desired End State

A push to any branch or a PR triggers one workflow that runs two parallel, path-gated jobs
— backend (Maven + Testcontainers Postgres) and frontend (pnpm lint + build) — both green
with no secrets. Local, Docker, and CI all agree on Node 24 / pnpm 11, and `backend/mvnw`
runs on Linux.

## Key Decisions Made

| Decision                     | Choice                                             | Why (1 sentence)                                                                 | Source   |
| ---------------------------- | -------------------------------------------------- | -------------------------------------------------------------------------------- | -------- |
| Frontend toolchain versions  | Node 24 / pnpm 11                                  | Adopt the documented source of truth; pnpm 11 reads lockfile 9.0 without churn.   | Plan     |
| Where to pin versions        | In-repo (`packageManager` + `engines` + `.nvmrc`) and reconcile the Dockerfile | One source of truth so local, Docker, CI, and corepack all agree; kills the drift. | Plan     |
| Workflow topology            | One workflow, two path-gated jobs                  | Single CI status, jobs run in parallel, a one-sided change skips the other app.   | Plan     |
| Triggers                     | `push` (all branches) + `pull_request`             | Matches F-02's "every push" and the test-plan's "CI on PR"; covers direct main commits. | Plan     |
| Maven wrapper exec bit       | Permanent fix via `git update-index --chmod=+x`    | Fixes `./mvnw` on Linux for everyone, no per-run `chmod` in the workflow.          | Research |
| Compose-in-tests flag        | Omit `-Dspring.docker.compose.enabled=false`       | Verified: Spring Boot skips compose in tests by default (`skip.in-tests=true`).    | Research |
| Secrets in CI                | None                                               | Test profile hardcodes a dummy key and the LLM is mocked in-process.              | Research |

## Scope

**In scope:** frontend version pins (`package.json` + `.nvmrc`), Dockerfile reconcile,
Maven wrapper exec-bit fix, and one `.github/workflows/ci.yml` (backend Maven job +
frontend pnpm job, path-gated).

**Out of scope:** browser E2E (Playwright, unbuilt Phase 3), git hooks (Phase 4), AWS
deploy / promotion / observability (above-MVP, Parked), model-quality eval (never a CI
gate), branch-protection setup (a GitHub UI task).

## Architecture / Approach

One workflow triggered on push + PR. A first `changes` job (`dorny/paths-filter`) emits
`backend`/`frontend` booleans — needed because GitHub's native `paths:` filters are
workflow-level, not per-job. Two build jobs run in parallel, each gated by `if:` on its
output: **backend** = `setup-java` Temurin 25 + `~/.m2` cache → `./mvnw clean package`
(Docker-backed Testcontainers, preinstalled on `ubuntu-latest`); **frontend** = `corepack
enable` → `setup-node` (reads `.nvmrc` + pnpm cache) → `install --frozen-lockfile` → `lint`
→ `build`. Adding `packageManager` lets corepack drive the pnpm version, so the Dockerfile
and the CI job both drop duplicated version literals.

## Phases at a Glance

| Phase                     | What it delivers                                                              | Key risk                                                                 |
| ------------------------- | ---------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| 1. Repo & toolchain prep  | Node 24 / pnpm 11 pinned in-repo, Dockerfile reconciled, `mvnw` executable; tree verified green locally | Version bump surfaces a pnpm-11 install or `next build` breakage         |
| 2. CI workflow            | `.github/workflows/ci.yml` with path-gated backend + frontend jobs on push/PR | Path-gating `needs`/`if:` wiring; remote verification needs a push       |

**Prerequisites:** a working GitHub remote (present: `morawski-dev/falcon-contracts`) and
push access; `HEAD` is currently 1 commit ahead of `origin/main` — push before expecting
Actions to run. `gh` CLI is not installed locally, so observe runs via the web UI. Docker
Desktop needed for the Phase 1 local backend verification.
**Estimated effort:** ~1 session across 2 phases (small, mechanical).

## Open Risks & Assumptions

- **Local verification assumes Docker Desktop is running** for the backend `clean package`
  (Testcontainers). If unavailable locally, that check moves to the first CI run.
- **pnpm 10 → 11 bump** is assumed lockfile-9.0-compatible (`--frozen-lockfile` holds); Phase
  1.4 verifies it. If it fails, fall back to pnpm 10 in `packageManager` (lockfile origin).
- **Path-gated jobs + future branch protection**: a skipped required check never reports. Not
  a problem today (no branch protection), but when it's added, gate on an aggregation
  `ci-success` job that `needs` both — noted for later, not built now.
- **Third-party action** `dorny/paths-filter@v3` is trusted at a major tag; SHA-pin later if
  hardening.

## Success Criteria (Summary)

- Every push / PR runs CI; both apps' jobs pass green on the current tree, no secrets set.
- A one-sided change runs only the affected app's job; a direct `main` push triggers CI.
- Local, Docker, and CI agree on Node 24 / pnpm 11, and `./mvnw` runs on Linux.

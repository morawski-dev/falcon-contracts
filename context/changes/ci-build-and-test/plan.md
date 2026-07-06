# CI — Build & Test on Every Push (roadmap F-02) Implementation Plan

## Overview

Add a single GitHub Actions workflow that builds both apps and runs their full test
suites on every push and pull request — the automated verification path F-02 exists to
provide. Because the frontend has no toolchain pins today and the Maven wrapper is
non-executable in the git index, a first phase makes the working tree CI-ready
(deterministic frontend versions + an executable wrapper) before the second phase wires
the workflow onto it.

CI here is a thin wrapper over commands that already pass locally — the substance is
green; the work is provisioning (JDK/Node/pnpm + Docker), one change-detection job, and
resolving the version drift the codebase currently contradicts itself on.

## Current State Analysis

- **No CI exists.** There is no `.github/` directory at all (`git`-confirmed) — the
  workflow is net-new.
- **Two standalone apps, no root build.** `backend/` (Maven) and `frontend/` (pnpm) each
  build from their own directory; there is no aggregator POM, pnpm workspace, or
  Turborepo/Nx. Every CI step must set `working-directory`.
- **Backend is CI-ready in substance.** `cd backend && ./mvnw -B -ntp clean package`
  compiles and runs all 10 test classes, including the load-bearing mocked-LLM e2e
  `AnalysisFlowTest.pasteRiskyContractIsSavedFlaggedAndLinked()`
  (`backend/src/test/java/com/morawski/dev/falcon/analysis/AnalysisFlowTest.java:115`).
  8 of 10 classes are `@SpringBootTest` that get Postgres from Testcontainers
  (`postgres:18`, `backend/src/test/java/com/morawski/dev/falcon/TestcontainersConfiguration.java:12-16`)
  — so the backend job needs a Docker daemon, which `ubuntu-latest` provides.
- **No secrets required.** The test profile hardcodes a dummy
  `spring.ai.openai.api-key=test` (`backend/src/test/resources/application.properties:3`)
  that shadows the `${OPENROUTER_API_KEY}` placeholder
  (`backend/src/main/resources/application.properties:7`), and the LLM is mocked
  in-process. The frontend build reads only `NEXT_PUBLIC_API_BASE_URL` with a hardcoded
  fallback (`frontend/src/lib/api.ts:1`).
- **Frontend has zero toolchain pins.** No `packageManager`, no `engines`, no `.nvmrc`
  (`frontend/package.json:1-33`). The repo's two sources disagree: `frontend/Dockerfile`
  pins `node:22-alpine` (`:8,16,27`) + `pnpm@10` (`:10,18`), while
  `docs/tech-stack.md` / `context/foundation/tech-stack.md` mandate Node 24 LTS / pnpm
  11. The lockfile is `lockfileVersion: '9.0'` (read by pnpm 9/10/11).
  `@types/node: ^24` is already present (`frontend/package.json:25`), so Node 24 was the
  intent all along.
- **The Maven wrapper is non-executable in the git index.**
  `git ls-files --stage backend/mvnw` reports mode `100644` — on a Linux runner `./mvnw`
  fails with "Permission denied" unless the bit is fixed or the script is invoked via
  `bash`.
- **Frontend test surface = lint + build only, by design.** There is no `test` script and
  no test runner installed; browser E2E is a later, unbuilt phase
  (`context/foundation/test-plan.md:130,150`). Lint + build today is expected, not a gap.

### Key Discoveries:

- **`spring-boot-docker-compose` does NOT run during tests.** Spring Boot 4.0 disables
  Docker Compose support in tests by default (`spring.docker.compose.skip.in-tests` =
  `true`, verified against the 4.0 reference docs). `./mvnw test` never starts a compose
  stack, regardless of any compose file — so no flag is needed on the Maven command.
- **GitHub's `paths:` filters are workflow-level, not job-level.** "Two path-filtered
  jobs in one workflow" requires a `changes` detection job whose outputs gate each build
  job via `if:` — the one non-obvious piece of the workflow.
- **GitHub remote exists:** `origin = https://github.com/morawski-dev/falcon-contracts.git`.
  Actions will run once the workflow is pushed; the local `gh` CLI is **not installed**, so
  observing runs is done via the web UI.

## Desired End State

Pushing to the repo (any branch) or opening a PR triggers `.github/workflows/ci.yml`,
which runs two parallel jobs — backend (`./mvnw clean package`, Testcontainers-backed) and
frontend (`pnpm install --frozen-lockfile` → `lint` → `build`) — each gated to run only
when its app changed. Both go green on the current tree with no secrets configured. Local,
Docker, and CI all agree on Node 24 / pnpm 11, and `backend/mvnw` is executable on Linux.

**Verify**: after pushing, the Actions tab shows both jobs passing; a frontend-only change
skips the backend job (and vice versa); a direct push to `main` triggers CI.

## What We're NOT Doing

- **No browser E2E (Playwright).** Test-plan Phase 3, unbuilt — out of scope
  (`context/foundation/test-plan.md:150`).
- **No git hooks** (per-edit / pre-commit). Test-plan Phase 4, "recommended", local-only
  (`context/foundation/test-plan.md:151-152`).
- **No deploy / promotion / AWS / observability.** Above-MVP and Parked; CI flow is
  manual-promotion (build+test only, deploy is a separate human step)
  (`context/foundation/roadmap.md` Parked; `context/foundation/tech-stack.md:10`).
- **No model-quality eval** in CI — Risk #6 is explicitly never a CI gate
  (`context/foundation/test-plan.md:198`).
- **No branch-protection / required-checks configuration** — that's a repo-settings task
  the user does in the GitHub UI, not a workflow file. (See Open Risks for the
  skipped-check caveat when it's added later.)
- **No `-Dspring.docker.compose.enabled=false` flag** — verified unnecessary (compose is
  skipped in tests by default).

## Implementation Approach

Two phases, in dependency order. Phase 1 changes production config (`frontend/package.json`,
`frontend/Dockerfile`, a new `.nvmrc`) and the wrapper's index mode so the tree is
deterministic and Linux-runnable, and verifies it's green locally. Phase 2 adds the single
workflow that depends on those facts (it reads the pinned `.nvmrc`, relies on the fixed
exec bit, and drives the same commands Phase 1 verified). Splitting this way means the
workflow is only ever wired onto a tree already proven green — CI never debugs a
pre-existing breakage.

The frontend reconcile makes `package.json` the one source of truth: adding
`packageManager` lets corepack auto-detect pnpm, so both the Dockerfile and the CI job
drop their duplicated version literals and just `corepack enable`.

## Critical Implementation Details

- **Path filtering needs a change-detection job.** GitHub's native `paths:` gate the whole
  workflow, not individual jobs. The workflow uses a first `changes` job (via
  `dorny/paths-filter`) that emits `backend` / `frontend` boolean outputs; the two build
  jobs run only when their output is `'true'`. This is the one snippet the plan pins (Phase
  2), because getting the `needs` + `if:` wiring wrong silently runs (or skips) the wrong
  jobs.
- **corepack + `setup-node` cache ordering.** `corepack enable` must run before
  `actions/setup-node`, so the pnpm shim is on `PATH` when setup-node's `cache: pnpm`
  resolves the store path. Order matters; the reverse fails to find pnpm.
- **The exec-bit fix is a git index change, not a file edit.** `git update-index
  --chmod=+x backend/mvnw` flips the tracked mode to `100755`; nothing in the file content
  changes. Only `backend/mvnw` needs it — `backend/mvnw.cmd` is Windows-only and stays
  `100644`.

## Phase 1: Repo & Toolchain Prep

### Overview

Make the working tree CI-ready and drift-free: pin the frontend to Node 24 / pnpm 11 in
one authoritative place, reconcile the Dockerfile to match, and make the Maven wrapper
executable on Linux. Prove the tree is green locally before any CI is wired onto it.

### Changes Required:

#### 1. Pin the frontend toolchain in `package.json`

**File**: `frontend/package.json`

**Intent**: Give corepack and every reader one authoritative version source, replacing the
"no pins anywhere" state so local, Docker, and CI stop disagreeing.

**Contract**: Add a top-level `packageManager` field pinning a concrete pnpm 11.x
(e.g. `"pnpm@11.9.0"`) and an `engines` field with `"node": ">=24"`. Do not change scripts
or dependencies. The concrete pnpm patch is the implementer's pick (current 11.x, ≥ 11.9
per tech-stack).

#### 2. Add a Node version file

**File**: `frontend/.nvmrc` (new)

**Intent**: A single-line pin that `actions/setup-node` (`node-version-file:`) and local
`nvm` both read, so the CI Node version isn't hardcoded in yaml.

**Contract**: One line: `24`.

#### 3. Reconcile the Dockerfile to the pinned toolchain

**File**: `frontend/Dockerfile`

**Intent**: Bring the deploy image onto Node 24 / pnpm 11 and let it inherit the pnpm
version from `packageManager` (via corepack) instead of a duplicated literal — killing the
last source of drift.

**Contract**: Change all three `FROM node:22-alpine` stages (`:8,16,27`) to
`node:24-alpine`. Replace both `RUN npm install -g pnpm@10` lines (`:10,18`) with
`RUN corepack enable` (corepack ships in the `node:24-alpine` image and resolves pnpm from
`packageManager`). Update the stale header comment (`:3-5`) that claims there is no
`packageManager` field. Leave the standalone-output runtime stage logic otherwise intact.

#### 4. Make the Maven wrapper executable on Linux

**File**: `backend/mvnw` (index mode only)

**Intent**: Fix the `100644` mode so `./mvnw` runs on a Linux runner without a per-job
`chmod`, permanently, for every contributor and CI alike.

**Contract**: `git update-index --chmod=+x backend/mvnw` — the tracked mode becomes
`100755`; file content is unchanged. `backend/mvnw.cmd` is left at `100644`.

### Success Criteria:

#### Automated Verification:

- `packageManager` + `engines.node` present: `grep -E '"packageManager"|"engines"' frontend/package.json`
- `.nvmrc` exists and reads `24`: `cat frontend/.nvmrc`
- Wrapper is executable in the index: `git ls-files --stage backend/mvnw` shows mode `100755`
- Frontend installs clean under pnpm 11: `cd frontend && corepack enable && pnpm install --frozen-lockfile`
- Frontend lint passes: `cd frontend && pnpm lint`
- Frontend build passes: `cd frontend && pnpm build`
- Backend suite passes locally (Docker required): `cd backend && ./mvnw -B -ntp clean package`

#### Manual Verification:

- Reconciled Docker image builds on Node 24 / pnpm 11: `docker build -t falcon-frontend ./frontend` completes (confirms the corepack + Node 24 reconcile didn't break the image).
- `pnpm lint` output is clean of new errors introduced by the version bump (scan for any pnpm-11-specific warnings).

**Implementation Note**: After Phase 1's automated verification passes, pause for manual
confirmation (the Docker build) before starting Phase 2. Phase blocks use plain bullets;
the checkboxes live in `## Progress`.

---

## Phase 2: CI Workflow

### Overview

Add the single GitHub Actions workflow: `push` (all branches) + `pull_request` triggers, a
change-detection job, and two parallel path-gated build jobs that run the commands Phase 1
proved green.

### Changes Required:

#### 1. The CI workflow

**File**: `.github/workflows/ci.yml` (new)

**Intent**: One workflow, one CI status, that builds+tests both apps on every push/PR,
running each app's job only when that app changed.

**Contract**: `name: CI`; `on: push` (no branch filter = all branches) and `pull_request`.
Three jobs:

- **`changes`** — checks out and runs `dorny/paths-filter` to emit `backend` and
  `frontend` boolean outputs. Filters: `backend/**` and `.github/workflows/ci.yml` →
  `backend`; `frontend/**` and `.github/workflows/ci.yml` → `frontend` (a workflow edit
  re-runs both).
- **`backend`** — `needs: changes`, `if: needs.changes.outputs.backend == 'true'`,
  `working-directory: backend`. Steps: checkout → `actions/setup-java@v4`
  (`distribution: temurin`, `java-version: 25`) → cache `~/.m2/repository` + `~/.m2/wrapper`
  keyed on `hashFiles('backend/pom.xml')` → `./mvnw -B -ntp clean package`. No secrets;
  Docker is preinstalled on `ubuntu-latest` for Testcontainers.
- **`frontend`** — `needs: changes`, `if: needs.changes.outputs.frontend == 'true'`,
  `working-directory: frontend`. Steps: checkout → `corepack enable` →
  `actions/setup-node@v4` (`node-version-file: frontend/.nvmrc`, `cache: pnpm`,
  `cache-dependency-path: frontend/pnpm-lock.yaml`) → `pnpm install --frozen-lockfile` →
  `pnpm lint` → `pnpm build`.

The non-obvious wiring — the detection job and the `if:` gates — pinned so the gating is
unambiguous:

```yaml
jobs:
  changes:
    runs-on: ubuntu-latest
    outputs:
      backend: ${{ steps.filter.outputs.backend }}
      frontend: ${{ steps.filter.outputs.frontend }}
    steps:
      - uses: actions/checkout@v4
      - uses: dorny/paths-filter@v3
        id: filter
        with:
          filters: |
            backend:
              - 'backend/**'
              - '.github/workflows/ci.yml'
            frontend:
              - 'frontend/**'
              - '.github/workflows/ci.yml'
  backend:
    needs: changes
    if: ${{ needs.changes.outputs.backend == 'true' }}
    # ...setup-java 25, m2 cache, ./mvnw -B -ntp clean package (working-directory: backend)
  frontend:
    needs: changes
    if: ${{ needs.changes.outputs.frontend == 'true' }}
    # ...corepack enable, setup-node (.nvmrc + pnpm cache), install --frozen-lockfile, lint, build
```

Pin third-party/first-party actions to major tags (`@v4`, `@v3`); SHA-pinning is a later
hardening option, not required for this MVP.

### Success Criteria:

#### Automated Verification:

- Workflow file exists: `test -f .github/workflows/ci.yml`
- Workflow is valid: `actionlint .github/workflows/ci.yml` passes (if `actionlint` is available); otherwise a YAML parse (`python -c "import yaml,sys; yaml.safe_load(open('.github/workflows/ci.yml'))"`) succeeds.

#### Manual Verification:

- After pushing the branch / opening a PR, both `backend` and `frontend` jobs appear in the Actions tab and pass green.
- A commit touching only `frontend/**` skips the `backend` job; a commit touching only `backend/**` skips the `frontend` job (path gating works).
- A direct push to `main` triggers the workflow (confirms "on every push").
- The backend job completes without any secret configured (confirms the dummy-key/mocked-LLM path).

**Implementation Note**: The Manual Verification here is inherently remote — it requires the
workflow to be pushed to GitHub. See Prerequisites in the brief: `HEAD` is currently ahead
of `origin/main`, and observing runs is done via the web UI (`gh` is not installed locally).

---

## Testing Strategy

### Unit / Integration Tests:

- No new application tests — this change adds CI, it does not change product code. The
  backend suite (incl. the mocked-LLM e2e) and the frontend lint/build ARE the tests CI
  runs; Phase 1 verifies they pass locally, Phase 2 verifies they pass on GitHub.

### Manual Testing Steps:

1. Complete Phase 1 local verification (frontend install/lint/build under pnpm 11; backend
   `clean package` under Docker; `git ls-files --stage backend/mvnw` = `100755`).
2. Build the reconciled Docker image to confirm the Node 24 / corepack change is sound.
3. After Phase 2, push to a feature branch; watch both jobs go green in the Actions tab.
4. Push a frontend-only commit; confirm the backend job is skipped. Repeat mirror for
   backend-only.
5. Push directly to `main`; confirm CI fires.

## Performance Considerations

- The backend job dominates wall-clock (JVM + Testcontainers Postgres pull/boot). The
  `~/.m2` cache (incl. `~/.m2/wrapper` so Maven 3.9.16 isn't re-downloaded) and pnpm store
  cache keep repeat runs fast. Path gating avoids paying the backend Testcontainers cost on
  a frontend-only change.
- `next build` downloads Geist fonts via `next/font/google` at build time
  (`frontend/src/app/layout.tsx:2-13`) — fine on GitHub runners (they have internet); only
  a concern in a network-restricted runner.

## Migration Notes

- The exec-bit change and the `packageManager`/Dockerfile bump are one-time. After Phase 1,
  a Windows contributor re-adding `backend/mvnw` could theoretically reset the mode; if that
  ever happens, `./mvnw` failing on CI is the signal and `git update-index --chmod=+x`
  re-applies. (A `bash ./mvnw` fallback in the workflow is available if a per-run guard is
  ever wanted, but is omitted to keep the workflow clean.)

## References

- Research: `context/changes/ci-build-and-test/research.md`
- Roadmap item F-02: `context/foundation/roadmap.md:75-83`
- Gate matrix: `context/foundation/test-plan.md:145-153`
- Load-bearing e2e: `backend/src/test/java/com/morawski/dev/falcon/analysis/AnalysisFlowTest.java:115`
- Testcontainers config: `backend/src/test/java/com/morawski/dev/falcon/TestcontainersConfiguration.java:12-16`
- Current Dockerfile pins: `frontend/Dockerfile:8,10,16,18,27`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Repo & Toolchain Prep

#### Automated

- [x] 1.1 `packageManager` + `engines.node` present in `frontend/package.json`
- [x] 1.2 `.nvmrc` exists and reads `24`
- [x] 1.3 `backend/mvnw` index mode is `100755`
- [x] 1.4 Frontend installs clean under pnpm 11 (`corepack enable && pnpm install --frozen-lockfile`)
- [x] 1.5 Frontend lint passes (`pnpm lint`)
- [x] 1.6 Frontend build passes (`pnpm build`)
- [x] 1.7 Backend suite passes locally (`./mvnw -B -ntp clean package`)

#### Manual

- [x] 1.8 Reconciled Docker image builds on Node 24 / pnpm 11 (`docker build ./frontend`)
- [x] 1.9 `pnpm lint` output clean of new version-bump warnings

### Phase 2: CI Workflow

#### Automated

- [ ] 2.1 `.github/workflows/ci.yml` exists
- [ ] 2.2 Workflow is valid (`actionlint`, or YAML parse fallback)

#### Manual

- [ ] 2.3 Both `backend` and `frontend` jobs pass green in the Actions tab
- [ ] 2.4 A frontend-only commit skips the backend job (and mirror for backend-only)
- [ ] 2.5 A direct push to `main` triggers the workflow
- [ ] 2.6 The backend job completes with no secret configured

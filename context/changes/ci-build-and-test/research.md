---
date: 2026-07-06T23:17:31+02:00
researcher: Mateusz Morawski
git_commit: ecbde9544a21d8316787f0c5bd2d2aeaeb2b459e
branch: main
repository: falcon-contracts
topic: "CI (build + test) for the Falcon monorepo — GitHub Actions, roadmap F-02"
tags: [research, codebase, ci, github-actions, maven, pnpm, testcontainers, f-02]
status: complete
last_updated: 2026-07-06
last_updated_by: Mateusz Morawski
---

# Research: CI (build + test) for the Falcon monorepo — roadmap F-02

**Date**: 2026-07-06T23:17:31+02:00
**Researcher**: Mateusz Morawski
**Git Commit**: ecbde9544a21d8316787f0c5bd2d2aeaeb2b459e (`ecbde95`)
**Branch**: main
**Repository**: falcon-contracts (remote: `https://github.com/morawski-dev/falcon-contracts.git`)

## Research Question

What does the Falcon monorepo need in order to add GitHub Actions CI that **builds both apps and runs their tests on every push** — roadmap item **F-02** (`ci-build-and-test`)? Scope was locked to **today's required gates only** (backend compile + full test suite incl. the mocked-LLM e2e via Testcontainers; frontend lint + build). Browser E2E, git hooks, deploy, and observability are explicitly out of scope / parked.

## Summary

CI here is a **thin wrapper around commands that already work locally** — the codebase is CI-ready in substance; the work is almost entirely in the GitHub Actions plumbing, plus resolving a handful of pins/traps. Key conclusions:

- **Two independent, standalone apps, no root build.** `backend/` (Maven) and `frontend/` (pnpm) each build from their own directory. Every CI step must set `working-directory`. There is no root aggregator POM, no pnpm workspace, no Turborepo/Nx.
- **The whole "today" gate set collapses to three commands:** backend `./mvnw … clean package` (compiles + runs all 10 test classes including the load-bearing mocked-LLM e2e), and frontend `pnpm install --frozen-lockfile` → `pnpm lint` → `pnpm build`.
- **No secrets required.** The backend test profile hardcodes a dummy `spring.ai.openai.api-key=test` and the LLM is mocked in-process, so the suite runs against an empty secrets context. The frontend build has a hardcoded API-base-URL fallback.
- **Docker is a hard requirement for the backend job** — 8 of 10 test classes are `@SpringBootTest` that get their Postgres from Testcontainers (`postgres:18` + Ryuk). GitHub's `ubuntu-latest` ships Docker, so this is satisfied out of the box.
- **Four traps the plan must handle**, in priority order: (1) the Maven wrapper is **non-executable in the git index** (`100644`) → `chmod +x` or `bash ./mvnw` on Linux; (2) JDK 25 provisioning (Temurin 25 is GA); (3) the frontend has **no version pins at all** (Node/pnpm) — CI must hardcode them, and the repo's own sources disagree (Dockerfile says pnpm 10 / Node 22; tech-stack says pnpm 11 / Node 24); (4) confirm `HEAD` is actually pushed to GitHub before relying on Actions.
- **A non-trap that looked like one:** `spring-boot-docker-compose` is on the runtime classpath, but Spring Boot **disables Docker Compose support in tests by default** (`spring.docker.compose.skip.in-tests` = `true`), so `./mvnw test` never tries to start a compose stack. No flag needed. (Verified against Spring Boot 4.0 reference docs — see Architecture Insights.)

## Detailed Findings

### Backend build & test toolchain (`backend/`)

**Module shape.** Single Maven module under `backend/`, packaging `jar`, no parent aggregator — CI runs from `backend/`.

**Versions & entrypoint.**
- Java **25** — `backend/pom.xml:30` (`<java.version>25</java.version>`). No `maven-toolchains-plugin`, so the JDK running Maven must itself be 25. Temurin 25 is GA (LTS, Sept 2025) and installs via `actions/setup-java@v4` (`distribution: temurin`, `java-version: 25`).
- Spring Boot **4.0.7** parent — `backend/pom.xml:5-10`. Spring AI BOM **2.0.0** — `backend/pom.xml:31`.
- Maven wrapper is **`only-script`** type and downloads **Apache Maven 3.9.16** on first run — `backend/.mvn/wrapper/maven-wrapper.properties`. Both `backend/mvnw` and `backend/mvnw.cmd` are present.

**Commands (Linux/CI form).**
- Compile only: `./mvnw -B -ntp compile` (or `test-compile` to also compile tests).
- Full test / build: `./mvnw -B -ntp clean package` (equivalently `./mvnw -B -ntp test`). There is **no Failsafe/`*IT` split** — `backend/pom.xml:149-176` declares only `spring-boot-maven-plugin` and `maven-compiler-plugin`, so Surefire runs **all** `*Test`/`*Tests` classes on the `test` phase.

**Test inventory** (10 classes; 8 need Docker):

| Test class | Kind | Docker? |
|---|---|---|
| `analysis/AnalysisFlowTest` | `@SpringBootTest` + MockMvc + `@Import(Testcontainers, MockChatModelConfig)` | **yes** |
| `analysis/AnalysisFailureTest` | `@SpringBootTest` + MockMvc + Testcontainers | **yes** |
| `analysis/AnalysisRepositoryTest` | `@SpringBootTest` + Testcontainers | **yes** |
| `auth/AuthBoundaryMatrixTest` | `@SpringBootTest` + MockMvc + Testcontainers | **yes** |
| `auth/AuthControllerTest` | `@SpringBootTest` + MockMvc + Testcontainers | **yes** |
| `auth/AuthFlowTest` | `@SpringBootTest` + MockMvc + Testcontainers | **yes** |
| `UserRepositoryTest` | `@SpringBootTest` + Testcontainers | **yes** |
| `FalconApplicationTests` | `@SpringBootTest` + Testcontainers | **yes** |
| `analysis/ContractAnalysisServiceTest` | plain JUnit (stub `ChatClient` lambda) | no |
| `auth/SecurityUtilsTest` | plain JUnit (`SecurityContextHolder`) | no |

**The load-bearing mocked-LLM e2e (F-02's named test).**
- `backend/src/test/java/com/morawski/dev/falcon/analysis/AnalysisFlowTest.java` — method `pasteRiskyContractIsSavedFlaggedAndLinked()` (`AnalysisFlowTest.java:115`) asserts a risky clause is saved `HIGH`/`AUTO_RENEWAL` with a linked `NegotiationPoint`. Sibling `crossUserCannotReadAnotherUsersAnalysis()` (`AnalysisFlowTest.java:139`) asserts cross-user 404.
- Runs under **plain `./mvnw test`** — no `@Tag`, no profile, no `*IT` naming. The `ChatModel` is mocked as a `@Bean @Primary` **lambda** (not a Mockito mock, to dodge a `getOptions()` NPE) returning fixed Polish JSON — `AnalysisFlowTest.java:50-68` (`FIXED_JSON`), `AnalysisFlowTest.java:187-197` (`MockChatModelConfig`). OpenRouter is never called → deterministic and free in CI.

**Database in tests comes only from Testcontainers.** No `spring.datasource.*` is configured anywhere; the datasource is supplied by `TestcontainersConfiguration.postgresContainer()` via `@ServiceConnection` — `backend/src/test/java/com/morawski/dev/falcon/TestcontainersConfiguration.java:12-16` (`DockerImageName.parse("postgres:18")`). Images pulled: `postgres:18` + `testcontainers/ryuk`. `ubuntu-latest` has Docker preinstalled → sufficient; no `services:` block needed.

**No secret needed.** `backend/src/main/resources/application.properties:7` uses `${OPENROUTER_API_KEY}`, but `backend/src/test/resources/application.properties:3` sets a literal `spring.ai.openai.api-key=test` and fully shadows the main file on the test classpath. No test reads `System.getenv`/`@Value` for the key. CI runs with an empty secrets context.

**Schema drift is caught for free.** `ddl-auto=validate` (main + test props) means Hibernate validates against the Liquibase-migrated schema (`001-create-users.yaml`, `002-create-analyses.yaml`) at context startup — an entity/migration mismatch fails the CI build as a context-load error.

**Caching.** Local repo `~/.m2/repository`, key `hashFiles('backend/pom.xml')` (single POM = complete key). Also cache `~/.m2/wrapper` to avoid re-downloading Maven 3.9.16 each run. Note: `actions/setup-java`'s built-in `cache: maven` hashes from repo root, not `backend/` — a dedicated `actions/cache` step keyed on `backend/pom.xml` is cleaner.

### Frontend build & test toolchain (`frontend/`)

**Project shape.** Standalone pnpm project rooted at `frontend/` — no root `package.json`, `pnpm-workspace.yaml`, `turbo.json`, or `nx.json`. All frontend steps run with `working-directory: frontend`.

**Commands (mirror the Dockerfile).**
- `pnpm install --frozen-lockfile` — `frontend/Dockerfile:13`.
- `pnpm lint` → bare `eslint` (flat config) — `frontend/package.json:9`, `frontend/eslint.config.mjs`.
- `pnpm build` → `next build`, `output: "standalone"` — `frontend/package.json:8`, `frontend/next.config.ts`, `frontend/Dockerfile:22`.

**No version pins exist — CI must hardcode them (plan decision).**
- **No** `packageManager` field, **no** `engines`, **no** `.nvmrc`/`.node-version` (checked root + `frontend/`). `frontend/Dockerfile:3-5` explicitly notes the missing `packageManager` field.
- **Source-of-truth conflict:** `frontend/Dockerfile:8,10,16,18,27` pins **pnpm@10 + `node:22-alpine`**; `docs/tech-stack.md:62-63` / `context/foundation/tech-stack.md:24` mandate **pnpm 11 + Node 24 LTS**. Lockfile is `lockfileVersion: '9.0'` (`frontend/pnpm-lock.yaml`), which pnpm 9/10/11 all read — so `--frozen-lockfile` should hold under either, but the version must be chosen deliberately and verified.
- Recommended setup: `pnpm/action-setup` with an explicit `version:` (corepack can't auto-detect without a `packageManager` field), then `actions/setup-node` with `cache: 'pnpm'` and `cache-dependency-path: frontend/pnpm-lock.yaml` — install pnpm **before** setup-node.

**`next build` is self-contained.** All data-fetching pages are Client Components (`dashboard`, `analyses/new`, `analyses/[id]`, `(auth)/login`, `(auth)/register`) fetching at runtime; the API layer reads `document.cookie` (browser-only) — `frontend/src/lib/api.ts:12-13,21`. No `generateStaticParams`/`getServerSideProps`/`getStaticProps`/`export const dynamic`. Only env is `NEXT_PUBLIC_API_BASE_URL` with a hardcoded fallback `?? "http://localhost:8080"` — `frontend/src/lib/api.ts:1`. **No backend, DB, or secret needed at build.** One build-time network dependency: `next/font/google` (Geist) downloads fonts during `next build` — `frontend/src/app/layout.tsx:2-13` — fine on GitHub runners, flag only for network-restricted sandboxes.

**Test surface = lint + build only, by design.** No `test` script (`frontend/package.json:5-10`), no jest/vitest/playwright config or binary. Per `context/foundation/test-plan.md:130,150`, frontend risks are covered by the (unbuilt, Phase 3) browser E2E, not a JS unit runner — so lint+build today is expected, not a coverage gap.

**Caching.** `cache: 'pnpm'` + `cache-dependency-path: frontend/pnpm-lock.yaml` (default store `~/.local/share/pnpm/store`). Optionally cache `frontend/.next/cache` for incremental builds.

### The "today" CI gate matrix (what must run now)

Authoritative source: `context/foundation/test-plan.md:145-153` (§5 Quality Gates). Phases 1 (auth boundary) and 2 (S-01 classification/isolation) are both already shipped, so every "required after Phase 1/2" gate is **live now**:

| Gate | Command | Source |
|---|---|---|
| Frontend lint (eslint) | `pnpm lint` | `test-plan.md:147`; `frontend/package.json:9` |
| Frontend build | `pnpm build` | `test-plan.md:147`; `frontend/package.json:8` |
| Backend compile | (implied by package) | `test-plan.md:147` |
| Backend unit + integration | `./mvnw … clean package` | `test-plan.md:148` |
| Deterministic mocked-LLM e2e | part of the same `./mvnw` run | `test-plan.md:149` |

F-02's roadmap outcome restates this: "*both apps build and their tests — including S-01's deterministic, mocked-LLM e2e — run automatically on every push*" (`context/foundation/roadmap.md:79`). CI flow is **manual-promotion** (`docs/tech-stack.md`, `ci_default_flow: manual-promotion` in `context/foundation/tech-stack.md:10`) — build+test only; deploy is a separate human-gated step this workflow must NOT trigger.

## Code References

- `backend/pom.xml:30` — `<java.version>25</java.version>`.
- `backend/pom.xml:5-10` — Spring Boot 4.0.7 parent; `:31` — Spring AI BOM 2.0.0.
- `backend/pom.xml:149-176` — build plugins (no Failsafe → single Surefire test phase).
- `backend/.mvn/wrapper/maven-wrapper.properties` — Maven 3.9.16, `only-script` wrapper.
- `backend/src/test/java/com/morawski/dev/falcon/TestcontainersConfiguration.java:12-16` — `postgres:18` via `@ServiceConnection`.
- `backend/src/test/java/com/morawski/dev/falcon/analysis/AnalysisFlowTest.java:115,139` — load-bearing e2e + cross-user isolation; `:50-68,187-197` — in-process mocked `ChatModel`.
- `backend/src/main/resources/application.properties:7` — `${OPENROUTER_API_KEY}`; `backend/src/test/resources/application.properties:3` — dummy `api-key=test` shadow.
- `frontend/package.json:5-10` — scripts (no `test`); `:9` lint; `:8` build.
- `frontend/pnpm-lock.yaml` — `lockfileVersion: '9.0'` (committed).
- `frontend/Dockerfile:3-5,8,10,13,16,18,22,27` — reference build recipe; the pnpm@10 / node:22 pins that conflict with tech-stack.
- `frontend/src/lib/api.ts:1,12-13,21` — `NEXT_PUBLIC_API_BASE_URL` fallback; browser-only fetch (build-safe).
- `frontend/src/app/layout.tsx:2-13` — `next/font/google` build-time font download.
- `context/foundation/test-plan.md:145-153` — the gate matrix; `:98` — Phase 4 "Quality-gate wiring" owns F-02.
- `context/foundation/roadmap.md:79` — F-02 outcome; `context/foundation/tech-stack.md:9-10` — `ci_provider: github-actions`, `ci_default_flow: manual-promotion`.

> **On permalinks:** repo-relative paths above are used deliberately. The current `HEAD` (`ecbde95`) is one commit ahead of `origin/main`, so a GitHub blob permalink to this commit would 404 until pushed. Regenerate permalinks after the push if a durable link set is wanted.

## Architecture Insights

- **CI mirrors the local dev contract 1:1.** The backend wrapper + Testcontainers and the frontend pnpm scripts are the same commands a developer runs; CI adds no new build path, only provisioning (JDK/Node/pnpm) and Docker. This keeps "green locally ⇒ green in CI" honest.
- **Determinism is designed in, not bolted on.** The mocked-`ChatModel`-as-lambda pattern (`AnalysisFlowTest`) plus the dummy test key means the load-bearing e2e is free and stable in CI — the exact property F-02 exists to automate. `ddl-auto=validate` additionally turns schema/entity drift into a CI failure for free.
- **`spring-boot-docker-compose` does not interfere with tests — verified.** Spring Boot 4.0 reference docs (`reference/features/dev-services` → "Using Docker Compose in Tests"): *"By default, Spring Boot's Docker Compose support is disabled during tests. To enable it, set `spring.docker.compose.skip.in-tests` to `false`."* So `skip.in-tests` defaults to `true`; `@SpringBootTest` never starts a compose stack, regardless of whether a compose file is present. The `-Dspring.docker.compose.enabled=false` flag one might add is **optional** insurance, not a correctness requirement. (Two sub-agents disagreed on the mechanism; this doc resolves it against the framework docs.)
- **Manual-promotion keeps the blast radius small.** The workflow is a pass/fail signal only — no deploy credentials, no environment, no auto-promotion. That matches the delivery order (local → CI → prod) and the "keep it to build + test" instruction in the F-02 roadmap entry.

## Historical Context (from prior changes)

- `context/archive/2026-07-03-bootstrap-verification/verification.md` — scaffold verified at bootstrap (exit 0, no warnings). The one historical wrinkle (Initializr defaulting the parent to 4.1.0 vs the decided 4.0.7) is **already resolved** — `backend/pom.xml:8` reads `4.0.7`. Boot 4 renamed the web starter to `spring-boot-starter-webmvc` and uses per-module `-test` starters; no CI action, just don't be surprised by module names.
- `context/archive/2026-07-06-analyze-and-save-contract/` — S-01, the slice that introduced `AnalysisFlowTest`; its research documents the mocked-`ChatModel`-lambda decision and the dummy-test-key determinism that make the e2e CI-safe.
- `context/archive/2026-07-06-testing-auth-boundary-regression/` — added `AuthBoundaryMatrixTest`, one of the 8 Testcontainers-backed classes CI will run.
- `context/foundation/infrastructure.md` — the AWS deploy plan; cited here only to confirm it stays **parked** (above-MVP). It notes CI/CD as "the stack's stated next infra layer, above this decision," and confirms the test profile's dummy key means CI needs no real secret.

## Related Research

- `context/archive/2026-07-06-analyze-and-save-contract/research.md` — Spring AI structured-output + mocked-LLM test internals (the e2e F-02 automates).
- `context/foundation/test-plan.md` §3/§5 — the phased test rollout; F-02 is the Phase 4 "Quality-gate wiring" step.

## Open Questions (decisions for `/10x-plan`)

1. **Frontend pnpm version:** pin **10** (matches `frontend/Dockerfile` + lockfile origin) or **11** (per `tech-stack.md`)? Verify `--frozen-lockfile` holds under the chosen version.
2. **Frontend Node version:** pin **24** (tech-stack source-of-truth) or **22** (Dockerfile)? Recommend 24 and treat the Dockerfile's Node 22 as separate drift.
3. **Pin the toolchain in-repo?** Consider adding `packageManager` (+ maybe `engines`/`.nvmrc`) to `frontend/package.json` as part of this change so local, Docker, and CI stop disagreeing — or keep CI-only pins and defer the reconciliation.
4. **Fix the `mvnw` exec bit permanently** via `git update-index --chmod=+x backend/mvnw` (one-time, clean) vs `chmod +x`/`bash ./mvnw` in the workflow each run.
5. **Trigger policy & topology:** one workflow with two jobs vs two workflows; `on: push` (all branches) + `pull_request`; and whether to add `paths:` filters (`backend/**` vs `frontend/**`) so an unaffected job is skipped. test-plan says "on every push" and "CI on PR."
6. **Verify the scaffold is actually green** before wiring the gate: does `pnpm lint` pass (note `frontend/src/app/layout.tsx:16` still has the `"Create Next App"` placeholder metadata) and does `pnpm build` complete end-to-end? Run both once locally/in a scratch job.
7. **Push prerequisite:** confirm `HEAD` is pushed to `origin` (currently 1 commit ahead) and that push access works — Actions won't run until the workflow is on GitHub. Note: `gh` CLI is **not installed** on this machine, so scripting against GitHub (runs, branch protection) needs `gh` installed or the web UI.
8. **Belt-and-suspenders compose flag:** decide whether to add `-Dspring.docker.compose.enabled=false` to the backend command purely as future-proofing (not required today).

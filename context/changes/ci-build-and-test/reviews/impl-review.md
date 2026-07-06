<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: CI — Build & Test on Every Push (roadmap F-02)

- **Plan**: context/changes/ci-build-and-test/plan.md
- **Scope**: Phase 1 of 2 and Phase 2 of 2 (full plan)
- **Date**: 2026-07-07
- **Verdict**: APPROVED
- **Findings**: 0 critical, 0 warnings, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Summary

Both phases implemented exactly as planned — the drift-detection agent found full MATCH across every planned change (`frontend/package.json`, `frontend/.nvmrc`, `frontend/Dockerfile`, `.github/workflows/ci.yml`, and the `backend/mvnw` exec-bit, which landed via a documented concurrent commit rather than this change's own commits, with no content or intent divergence). The safety/quality agent found zero CRITICAL or WARNING issues — no secrets, no script-injection surface (the workflow never interpolates PR-controlled strings into a `run:` step), no YAML defects. All automated success criteria were re-verified live during this review: 42/42 backend tests pass (`BUILD SUCCESS`, up from 34 at Phase 1 time as a concurrent session added its own tests — confirms the CI command stays green under drift), frontend `lint`/`build` both clean, and the workflow YAML parses without error. All manual verification items (Docker image build, Actions-tab green runs, path-gating, main-push trigger, no-secret backend run) are confirmed via the plan's own Progress section.

Two OBSERVATION-level findings — both optional hardening, neither blocking.

## Findings

### F1 — No explicit `permissions:` block in the workflow

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: `.github/workflows/ci.yml:1-6`
- **Detail**: The workflow has no top-level `permissions:` key, so it inherits whatever default `GITHUB_TOKEN` permissions the repo/org has configured rather than declaring its own floor. Nothing in the workflow currently writes anything (no PR comments, releases, or deployments), so there's no live exploit path — but an explicit declaration removes the dependency on an out-of-repo setting and is a standard defense-in-depth practice for Actions workflows.
- **Fix**: Add `permissions: contents: read` at the top level of `.github/workflows/ci.yml`.
- **Decision**: FIXED

### F2 — `setup-node` pnpm cache-path resolution unverified for this monorepo's layout

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: `.github/workflows/ci.yml:67-71`
- **Detail**: `defaults.run.working-directory: frontend` only applies to `run:` steps, not to `uses:` steps like `actions/setup-node@v4`. Its cache lookup runs with the job's default (repo-root) working directory. In practice this is low-risk: the step is given an explicit, correct `cache-dependency-path: frontend/pnpm-lock.yaml` and pnpm's store path resolution is not cwd-dependent on Linux — but since `frontend/` isn't at the repo root, it's worth a one-time visual check of the first live run's cache-hit log line to confirm it behaves as expected.
- **Fix**: No code change needed now — confirm on the first real CI run that the frontend job's cache step reports a cache hit/restore as expected; only revisit if it doesn't.
- **Decision**: FIXED (no code change applicable; verify on the first live CI run's cache-step log)
